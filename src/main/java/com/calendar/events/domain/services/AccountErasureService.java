package com.calendar.events.domain.services;

import com.calendar.events.domain.models.DeletedUserMarker;
import com.calendar.events.domain.models.ErasureReport;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Erases what this service holds of an account wely-users deleted, marker first, every step safe to replay. */
public class AccountErasureService {

    /** Compare-and-set misses on one organized event before the erasure gives up and is retried whole. */
    static final int MAX_RELEASE_ATTEMPTS = 3;

    private static final System.Logger LOG = System.getLogger(AccountErasureService.class.getName());

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final BirthdayService birthdayService;
    private final FriendshipService friendshipService;
    private final ExpenseRepository expenseRepository;
    private final MediaStore mediaStore;
    private final DeletedUserMarkers markers;
    private final PollUpdates pollUpdates;
    /** Demo accounts: never erased, and never handed an event, since anyone may sign in as them. */
    private final Set<String> protectedUserIds;
    private final Clock clock;

    public AccountErasureService(EventRepository eventRepository, EventService eventService,
                                 BirthdayService birthdayService, FriendshipService friendshipService,
                                 ExpenseRepository expenseRepository,
                                 MediaStore mediaStore, DeletedUserMarkers markers, PollUpdates pollUpdates,
                                 Set<String> protectedUserIds, Clock clock) {
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.birthdayService = birthdayService;
        this.friendshipService = friendshipService;
        this.expenseRepository = expenseRepository;
        this.mediaStore = mediaStore;
        this.markers = markers;
        this.pollUpdates = pollUpdates;
        this.protectedUserIds = Set.copyOf(protectedUserIds);
        this.clock = clock;
    }

    /** Erases the account; a null {@code deletedAt} falls back to now, and the first recorded one always wins. */
    public Mono<ErasureReport> erase(String userId, Instant deletedAt) {
        if (protectedUserIds.contains(userId)) {
            // wely-users refuses to delete one; a marker would refuse the demo for good.
            LOG.log(System.Logger.Level.WARNING, "Deletion of protected account {0} ignored", userId);
            return Mono.just(ErasureReport.NONE);
        }
        return Mono.defer(() -> markers.markDeleted(userId, deletedAt != null ? deletedAt : clock.instant()))
                .map(DeletedUserMarker::deletedAt)
                .flatMap(at -> Mono.fromRunnable(() -> pollUpdates.disconnect(userId))
                        // Before the organized events: the birthday is the user's own, never handed over.
                        .then(Mono.defer(() -> birthdayService.forget(userId)))
                        // After the marker, which keeps a late FRIENDSHIP_CHANGED from storing a pair again.
                        .then(Mono.defer(() -> friendshipService.forget(userId)))
                        .then(Mono.defer(() -> withdrawBallots(userId))).map(ErasureReport.NONE::withBallotsWithdrawn)
                        .flatMap(report -> releaseOrganizedEvents(userId, at, report))
                        .flatMap(report -> eventRepository.leaveUpcoming(userId, at).map(report::withEventsLeft))
                        .flatMap(report -> eventRepository.clearUpcomingResponses(userId, at).map(report::withResponsesCleared))
                        .flatMap(report -> eventRepository.forgetInvitations(userId).map(report::withInvitationsWithdrawn))
                        .flatMap(report -> dropReceipts(userId).map(report::withReceiptsDropped)))
                .flatMap(report -> Mono.defer(() -> markers.markErased(userId, clock.instant())).thenReturn(report));
    }

    /** Runs before any hand-over, so a date the successor fixes cannot subscribe the user again. */
    private Mono<Long> withdrawBallots(String userId) {
        return eventRepository.findOpenPollsInvolving(userId)
                .filter(event -> event.getPoll().ballots().containsKey(userId))
                .concatMap(event -> eventRepository.castBallot(event.getId(), userId, Map.of())
                        .doOnNext(updated -> pollUpdates.publish(event.getId(), updated.getPoll())))
                .count();
    }

    private Mono<ErasureReport> releaseOrganizedEvents(String userId, Instant at, ErasureReport report) {
        return eventRepository.findOrganizedBy(userId)
                .concatMap(event -> release(event, userId, at, MAX_RELEASE_ATTEMPTS))
                .collectList()
                .map(releases -> report.withReleased(
                        releases.stream().filter(release -> release == Release.HANDED_OVER).count(),
                        releases.stream().filter(release -> release == Release.DELETED).count()));
    }

    /** Hands the event to the earliest participant neither deleted nor protected, or deletes it; a missed write decides again. */
    private Mono<Release> release(Event event, String userId, Instant at, int attemptsLeft) {
        if (attemptsLeft == 0) {
            return Mono.error(new TechnicalException(TechnicalErrorCode.ERASURE_INCOMPLETE));
        }
        return markers.deletedAmong(event.getParticipantIds())
                .flatMap(deleted -> {
                    Set<String> excluded = new HashSet<>(deleted);
                    excluded.addAll(protectedUserIds);
                    excluded.add(userId);
                    Optional<String> successor = event.successorExcluding(excluded);
                    Mono<Release> attempt = successor.isPresent()
                            ? handOver(event, userId, successor.get(), at)
                            : eventRepository.deleteIfAlone(event.getId(), userId, excluded)
                                    .flatMap(gone -> eventService.afterDeletion(gone).thenReturn(Release.DELETED));
                    return attempt.switchIfEmpty(Mono.defer(() -> eventRepository.findById(event.getId())
                            .filter(current -> userId.equals(current.getOrganizerId()))
                            .flatMap(current -> release(current, userId, at, attemptsLeft - 1))
                            .defaultIfEmpty(Release.SKIPPED)));
                });
    }

    private Mono<Release> handOver(Event event, String userId, String successorId, Instant at) {
        Mono<Event> write = event.isOverAt(at)
                ? eventRepository.handOverKeepingFormer(event.getId(), userId, successorId)
                : eventRepository.handOver(event.getId(), userId, successorId);
        return write.map(updated -> Release.HANDED_OVER);
    }

    /** Drops the receipts the user attached to their own expenses, the object before the key. */
    private Mono<Long> dropReceipts(String userId) {
        return expenseRepository.findWithReceiptRecordedBy(userId)
                .concatMap(this::dropReceipt)
                .filter(Boolean::booleanValue)
                .count();
    }

    private Mono<Boolean> dropReceipt(Expense expense) {
        return mediaStore.delete(expense.receiptKey())
                .then(Mono.defer(() -> expenseRepository.clearReceipt(expense.eventId(), expense.id(), expense.receiptKey())));
    }

    private enum Release { HANDED_OVER, DELETED, SKIPPED }
}
