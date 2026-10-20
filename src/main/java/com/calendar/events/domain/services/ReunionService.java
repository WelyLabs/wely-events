package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.ReunionSnoozes;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Optional;

/**
 * Close friends the user has drifted from. A candidate is a CLOSE or INTIMATE friend of at least
 * {@link #APART} standing, with no shared outing in the last {@link #APART} and nothing planned together,
 * and not snoozed by the user. The longest unseen come first, INTIMATE before CLOSE.
 */
public class ReunionService {

    static final Duration APART = Duration.ofDays(90);
    static final Duration SNOOZE = Duration.ofDays(30);
    public static final int SHOWN = 3;

    private static final Comparator<ReunionCandidate> MOST_DUE_FIRST =
            Comparator.comparing(ReunionCandidate::closeness).reversed()
                    .thenComparing(ReunionCandidate::lastSharedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ReunionCandidate::since, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ReunionCandidate::friendId);

    private final EventRepository eventRepository;
    private final FriendshipDirectory friendships;
    private final ReunionSnoozes snoozes;
    private final DisplayNames displayNames;
    private final Clock clock;
    private final ZoneId zone;

    public ReunionService(EventRepository eventRepository, FriendshipDirectory friendships, ReunionSnoozes snoozes,
                          DisplayNames displayNames, Clock clock, ZoneId zone) {
        this.eventRepository = eventRepository;
        this.friendships = friendships;
        this.snoozes = snoozes;
        this.displayNames = displayNames;
        this.clock = clock;
        this.zone = zone;
    }

    /** The {@value #SHOWN} most due candidates, for the "long time no see" card. */
    public Flux<ReunionCandidate> reunionsFor(String userId) {
        return candidatesFor(userId).take(SHOWN);
    }

    /** Every candidate of the user, the most due first, named. */
    public Flux<ReunionCandidate> candidatesFor(String userId) {
        Instant now = clock.instant();
        return snoozes.snoozedBy(userId, now)
                .flatMapMany(snoozed -> friendships.friendshipsOf(userId)
                        .filter(friendship -> isCloseEnoughAndOldEnough(friendship, now))
                        .filter(friendship -> !snoozed.contains(friendship.otherThan(userId)))
                        .concatMap(friendship -> candidate(userId, friendship, now)))
                .collectSortedList(MOST_DUE_FIRST)
                .flatMapMany(candidates -> displayNames.of(candidates.stream().map(ReunionCandidate::friendId).toList())
                        .flatMapIterable(names -> candidates.stream()
                                .map(candidate -> candidate.named(DisplayNames.nameIn(names, candidate.friendId())))
                                .toList()));
    }

    /** "Later": the friend is not suggested again for {@link #SNOOZE}. 404 for anyone but a friend. */
    public Mono<Void> dismiss(String userId, String friendId) {
        return friendships.pairOf(userId, friendId)
                .filter(Friendship::friends)
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.FRIEND_NOT_FOUND)))
                .flatMap(friendship -> snoozes.snooze(userId, friendId, clock.instant().plus(SNOOZE)));
    }

    private static boolean isCloseEnoughAndOldEnough(Friendship friendship, Instant now) {
        return friendship.friends() && friendship.closeness().compareTo(Closeness.CLOSE) >= 0
                && friendship.since() != null && !friendship.since().isAfter(now.minus(APART));
    }

    private Mono<ReunionCandidate> candidate(String userId, Friendship friendship, Instant now) {
        String friendId = friendship.otherThan(userId);
        return eventRepository.hasUpcomingShared(userId, friendId, now)
                .filter(planned -> !planned)
                .flatMap(nothingPlanned -> eventRepository.findLastSharedOuting(userId, friendId, now)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty()))
                .filter(last -> last.isEmpty() || last.get().getStartDate().isBefore(now.minus(APART)))
                .map(last -> new ReunionCandidate(friendId, null, friendship.closeness(), friendship.since(),
                        last.map(Event::getStartDate).orElse(null),
                        last.map(Event::getId).orElse(null),
                        last.map(Event::getTitle).orElse(null),
                        last.map(event -> monthsBetween(event.getStartDate(), now)).orElse(null)));
    }

    private int monthsBetween(Instant from, Instant to) {
        LocalDate start = from.atZone(zone).toLocalDate();
        LocalDate end = to.atZone(zone).toLocalDate();
        return (int) ChronoUnit.MONTHS.between(start, end);
    }
}
