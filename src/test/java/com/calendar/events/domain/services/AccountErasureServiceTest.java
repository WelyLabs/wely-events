package com.calendar.events.domain.services;

import com.calendar.events.domain.models.DeletedUserMarker;
import com.calendar.events.domain.models.ErasureReport;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.CommunityDirectory;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.FriendshipHistory;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountErasureServiceTest {

    private static final String USER = "user-gone";
    private static final String DEMO = "demo-camille";
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:00:00Z");
    private static final PollSlot SLOT = new PollSlot("s1", DELETED_AT.minusSeconds(86_400), DELETED_AT.minusSeconds(82_800));

    @Mock
    private EventRepository eventRepository;
    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private MediaStore mediaStore;
    @Mock
    private DeletedUserMarkers markers;
    @Mock
    private PollUpdates pollUpdates;
    @Mock
    private EventDeletionOutbox deletionOutbox;
    @Mock
    private BirthdayService birthdayService;
    @Mock
    private FriendshipDirectory friendships;
    @Mock
    private FriendshipHistory friendshipHistory;

    /** Accounts the register knows as deleted; the user being erased is one of them. */
    private final Set<String> deleted = new HashSet<>(Set.of(USER));

    private AccountErasureService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        EventService eventService = new EventService(eventRepository, mock(SocialGraph.class), expenseRepository,
                new PictureService(mediaStore), deletionOutbox, mock(CommunityDirectory.class), new AccountGuard(markers),
                clock);
        service = new AccountErasureService(eventRepository, eventService, birthdayService, friendships, friendshipHistory,
                expenseRepository, mediaStore, markers, pollUpdates, Set.of(DEMO), clock);
        lenient().when(birthdayService.forget(anyString())).thenReturn(Mono.just(0L));
        lenient().when(friendships.forget(anyString())).thenReturn(Mono.just(0L));
        lenient().when(friendshipHistory.forget(anyString())).thenReturn(Mono.just(0L));

        lenient().when(markers.markDeleted(anyString(), any()))
                .thenAnswer(call -> Mono.just(new DeletedUserMarker(call.getArgument(0), call.getArgument(1), null)));
        lenient().when(markers.deletedAmong(any())).thenAnswer(call -> Mono.just(((Collection<String>) call.getArgument(0))
                .stream().filter(deleted::contains).collect(Collectors.toSet())));
        lenient().when(markers.markErased(anyString(), any())).thenReturn(Mono.empty());
        lenient().when(eventRepository.findOpenPollsInvolving(anyString())).thenReturn(Flux.empty());
        lenient().when(eventRepository.findOrganizedBy(anyString())).thenReturn(Flux.empty());
        lenient().when(eventRepository.leaveUpcoming(anyString(), any())).thenReturn(Mono.just(0L));
        lenient().when(eventRepository.clearUpcomingResponses(anyString(), any())).thenReturn(Mono.just(0L));
        lenient().when(expenseRepository.findWithReceiptRecordedBy(anyString())).thenReturn(Flux.empty());
        lenient().when(deletionOutbox.record(any())).thenReturn(Mono.empty());
    }

    /** An event the user organizes, between {@code start} and {@code end}, with these participants in join order. */
    private static Event organized(String id, Instant start, Instant end, String... participants) {
        return Event.builder().id(id).title("Quiz").organizerId(USER).startDate(start).endDate(end)
                .visibility(Visibility.PRIVATE).participantIds(new ArrayList<>(List.of(participants))).groupVersion(2L)
                .build();
    }

    private static Event upcoming(String id, String... participants) {
        return organized(id, DELETED_AT.plusSeconds(86_400), DELETED_AT.plusSeconds(90_000), participants);
    }

    private static Event past(String id, String... participants) {
        return organized(id, DELETED_AT.minusSeconds(90_000), DELETED_AT.minusSeconds(86_400), participants);
    }

    private static Event withBallots(String id, String organizer, Map<String, Map<String, PollAnswer>> ballots) {
        return Event.builder().id(id).title("Dinner").organizerId(organizer).startDate(SLOT.start()).endDate(SLOT.end())
                .participantIds(new ArrayList<>(List.of(organizer)))
                .poll(new Poll(PollStatus.OPEN, List.of(SLOT), ballots, null)).build();
    }

    private void organizes(Event... events) {
        when(eventRepository.findOrganizedBy(USER)).thenReturn(Flux.just(events));
    }

    private void cascadeOf(String eventId) {
        when(expenseRepository.findByEvent(eventId)).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent(eventId)).thenReturn(Mono.empty());
    }

    private ErasureReport erase() {
        return service.erase(USER, DELETED_AT).block();
    }

    private static boolean hasCode(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    // --- Order and reference time -----------------------------------------------------------------

    @Test
    void erase_shouldRecordTheMarkerBeforeAnyOtherWrite() {
        erase();

        InOrder order = inOrder(markers, pollUpdates, eventRepository);
        order.verify(markers).markDeleted(USER, DELETED_AT);
        order.verify(pollUpdates).disconnect(USER);
        order.verify(eventRepository).findOpenPollsInvolving(USER);
        order.verify(eventRepository).findOrganizedBy(USER);
        order.verify(eventRepository).leaveUpcoming(USER, DELETED_AT);
    }

    @Test
    void erase_shouldForgetTheBirthdayAfterTheMarker_andBeforeTheOrganizedEvents() {
        erase();

        InOrder order = inOrder(markers, birthdayService, eventRepository);
        order.verify(markers).markDeleted(USER, DELETED_AT);
        order.verify(birthdayService).forget(USER);
        order.verify(eventRepository).findOrganizedBy(USER);
    }

    @Test
    void erase_shouldForgetTheFriendshipsAfterTheMarker_andBeforeTheOrganizedEvents() {
        erase();

        InOrder order = inOrder(markers, friendships, friendshipHistory, eventRepository);
        order.verify(markers).markDeleted(USER, DELETED_AT);
        order.verify(friendships).forget(USER);
        order.verify(friendshipHistory).forget(USER);
        order.verify(eventRepository).findOrganizedBy(USER);
    }

    @Test
    void erase_shouldFailForARetry_whenTheFriendshipHistoryCannotBePurged() {
        when(friendshipHistory.forget(USER)).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.erase(USER, DELETED_AT)).expectError(IllegalStateException.class).verify();
        verify(markers, never()).markErased(anyString(), any());
    }

    @Test
    void erase_shouldFailForARetry_whenTheFriendshipsCannotBeForgotten() {
        when(friendships.forget(USER)).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.erase(USER, DELETED_AT)).expectError(IllegalStateException.class).verify();
        verify(markers, never()).markErased(anyString(), any());
    }

    @Test
    void erase_shouldFailForARetry_whenTheBirthdayCannotBeForgotten() {
        when(birthdayService.forget(USER)).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.erase(USER, DELETED_AT)).expectError(IllegalStateException.class).verify();
        verify(markers, never()).markErased(anyString(), any());
    }

    @Test
    void erase_shouldClassifyEventsAgainstTheStoredDeletionTime_whenTheMarkerAlreadyExists() {
        Instant first = DELETED_AT.minusSeconds(3600);
        when(markers.markDeleted(USER, DELETED_AT)).thenReturn(Mono.just(new DeletedUserMarker(USER, first, null)));

        erase();

        verify(eventRepository).leaveUpcoming(USER, first);
        verify(eventRepository).clearUpcomingResponses(USER, first);
    }

    @Test
    void erase_shouldUseTheProcessingTime_whenDeletedAtIsNull() {
        service.erase(USER, null).block();

        verify(markers).markDeleted(USER, NOW);
        verify(eventRepository).leaveUpcoming(USER, NOW);
    }

    @Test
    void erase_shouldDisconnectTheUsersPollStreams() {
        erase();

        verify(pollUpdates).disconnect(USER);
    }

    @Test
    void erase_shouldLeaveAProtectedAccountAlone() {
        StepVerifier.create(service.erase(DEMO, DELETED_AT)).expectNext(ErasureReport.NONE).verifyComplete();

        verifyNoInteractions(markers, eventRepository, expenseRepository, mediaStore, pollUpdates, birthdayService);
    }

    // --- Ballots ------------------------------------------------------------------------------------

    @Test
    void erase_shouldWithdrawTheBallotOnEveryOpenPollAndPublishTheNewTally() {
        Event first = withBallots("p1", "lea", Map.of(USER, Map.of("s1", PollAnswer.YES)));
        Event second = withBallots("p2", "hugo", Map.of(USER, Map.of("s1", PollAnswer.NO)));
        Event firstAfter = withBallots("p1", "lea", Map.of());
        Event secondAfter = withBallots("p2", "hugo", Map.of());
        when(eventRepository.findOpenPollsInvolving(USER)).thenReturn(Flux.just(first, second));
        when(eventRepository.castBallot("p1", USER, Map.of())).thenReturn(Mono.just(firstAfter));
        when(eventRepository.castBallot("p2", USER, Map.of())).thenReturn(Mono.just(secondAfter));

        assertThat(erase().ballotsWithdrawn()).isEqualTo(2);

        verify(pollUpdates).publish("p1", firstAfter.getPoll());
        verify(pollUpdates).publish("p2", secondAfter.getPoll());
    }

    @Test
    void erase_shouldIgnoreOpenPollsTheUserDidNotVoteOn() {
        when(eventRepository.findOpenPollsInvolving(USER))
                .thenReturn(Flux.just(withBallots("p1", USER, Map.of("lea", Map.of("s1", PollAnswer.YES)))));

        assertThat(erase().ballotsWithdrawn()).isZero();

        verify(eventRepository, never()).castBallot(anyString(), anyString(), any());
    }

    @Test
    void erase_shouldKeepTheBallot_whenThePollClosedMeanwhile() {
        when(eventRepository.findOpenPollsInvolving(USER))
                .thenReturn(Flux.just(withBallots("p1", "lea", Map.of(USER, Map.of("s1", PollAnswer.YES)))));
        when(eventRepository.castBallot("p1", USER, Map.of())).thenReturn(Mono.empty());

        assertThat(erase().ballotsWithdrawn()).isZero();

        verify(pollUpdates, never()).publish(anyString(), any());
    }

    @Test
    void erase_shouldWithdrawBallotsBeforeHandingAnyEventOver() {
        when(eventRepository.findOpenPollsInvolving(USER))
                .thenReturn(Flux.just(withBallots("p1", "lea", Map.of(USER, Map.of("s1", PollAnswer.YES)))));
        when(eventRepository.castBallot("p1", USER, Map.of())).thenReturn(Mono.just(withBallots("p1", "lea", Map.of())));
        Event event = upcoming("e1", USER, "lea");
        organizes(event);
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.just(event));

        erase();

        InOrder order = inOrder(eventRepository);
        order.verify(eventRepository).castBallot("p1", USER, Map.of());
        order.verify(eventRepository).handOver("e1", USER, "lea");
    }

    // --- Organized events ---------------------------------------------------------------------------

    @Test
    void erase_shouldHandAnUpcomingEventOverToTheEarliestParticipant_removingTheUser() {
        Event event = upcoming("e1", USER, "lea", "hugo");
        organizes(event);
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.just(event));

        assertThat(erase().eventsHandedOver()).isEqualTo(1);

        verify(eventRepository, never()).handOverKeepingFormer(anyString(), anyString(), anyString());
    }

    @Test
    void erase_shouldHandAPastEventOverWithoutRemovingTheUser() {
        Event event = past("e1", USER, "lea");
        organizes(event);
        when(eventRepository.handOverKeepingFormer("e1", USER, "lea")).thenReturn(Mono.just(event));

        assertThat(erase().eventsHandedOver()).isEqualTo(1);

        verify(eventRepository, never()).handOver(anyString(), anyString(), anyString());
    }

    @Test
    void erase_shouldTreatAnOpenPollAsUpcoming_whenItsEarliestSlotIsPast() {
        Event poll = withBallots("e1", USER, Map.of());
        poll.getParticipantIds().add("lea");
        organizes(poll);
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.just(poll));

        erase();

        verify(eventRepository).handOver("e1", USER, "lea");
    }

    @Test
    void erase_shouldTreatAnEventEndingExactlyAtDeletionAsUpcoming() {
        Event event = organized("e1", DELETED_AT.minusSeconds(3600), DELETED_AT, USER, "lea");
        organizes(event);
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.just(event));

        erase();

        verify(eventRepository).handOver("e1", USER, "lea");
    }

    @Test
    void erase_shouldSkipDeletedParticipants_whenChoosingTheSuccessor() {
        deleted.add("also-gone");
        Event event = upcoming("e1", USER, "also-gone", "lea");
        organizes(event);
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.just(event));

        erase();

        verify(eventRepository).handOver("e1", USER, "lea");
    }

    @Test
    void erase_shouldNeverHandOverToAProtectedAccount() {
        Event event = upcoming("e1", USER, DEMO, "lea");
        organizes(event);
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.just(event));

        erase();

        verify(eventRepository, never()).handOver("e1", USER, DEMO);
        verify(eventRepository).handOver("e1", USER, "lea");
    }

    @Test
    void erase_shouldDeleteTheEvent_whenOnlyTheDemoAccountRemains() {
        Event event = upcoming("e1", USER, DEMO);
        organizes(event);
        when(eventRepository.deleteIfAlone("e1", USER, Set.of(USER, DEMO))).thenReturn(Mono.just(event));
        cascadeOf("e1");

        assertThat(erase().eventsDeleted()).isEqualTo(1);

        verify(eventRepository, never()).handOver(anyString(), anyString(), anyString());
        verify(deletionOutbox).record(new EventDeletion("e1", "Quiz", 3L, NOW));
    }

    @Test
    void erase_shouldDeleteTheEventThroughTheCascade_whenNoOtherParticipant() {
        Event event = past("e1", USER);
        event.setImage("events/e1/cover-a.jpg");
        organizes(event);
        when(eventRepository.deleteIfAlone("e1", USER, Set.of(USER, DEMO))).thenReturn(Mono.just(event));
        Expense withReceipt = new Expense("x1", "e1", "Courses", 1000, "EUR", USER, USER, ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share(USER, 0)), DELETED_AT, "events/e1/receipts/x1-a.jpg", null, 0L);
        when(expenseRepository.findByEvent("e1")).thenReturn(Flux.just(withReceipt));
        when(expenseRepository.deleteByEvent("e1")).thenReturn(Mono.empty());
        when(mediaStore.delete(anyString())).thenReturn(Mono.empty());

        ErasureReport report = erase();

        assertThat(report.eventsDeleted()).isEqualTo(1);
        assertThat(report.eventsHandedOver()).isZero();
        verify(deletionOutbox).record(new EventDeletion("e1", "Quiz", 3L, NOW));
        verify(expenseRepository).deleteByEvent("e1");
        verify(mediaStore).delete("events/e1/cover-a.jpg");
        verify(mediaStore).delete("events/e1/receipts/x1-a.jpg");
    }

    @Test
    void erase_shouldDeleteTheEvent_whenEveryOtherParticipantIsDeleted() {
        deleted.add("also-gone");
        Event event = past("e1", USER, "also-gone");
        organizes(event);
        when(eventRepository.deleteIfAlone("e1", USER, Set.of(USER, "also-gone", DEMO))).thenReturn(Mono.just(event));
        cascadeOf("e1");

        assertThat(erase().eventsDeleted()).isEqualTo(1);
    }

    @Test
    void erase_shouldDecideAgain_whenTheSuccessorLeftBeforeTheHandOver() {
        organizes(upcoming("e1", USER, "lea", "hugo"));
        Event reread = upcoming("e1", USER, "hugo");
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.just(reread));
        when(eventRepository.handOver("e1", USER, "hugo")).thenReturn(Mono.just(reread));

        assertThat(erase().eventsHandedOver()).isEqualTo(1);
    }

    @Test
    void erase_shouldDecideAgain_whenSomeoneJoinedBeforeTheDeletion() {
        organizes(upcoming("e1", USER));
        Event reread = upcoming("e1", USER, "lea");
        when(eventRepository.deleteIfAlone("e1", USER, Set.of(USER, DEMO))).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.just(reread));
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.just(reread));

        ErasureReport report = erase();

        assertThat(report.eventsHandedOver()).isEqualTo(1);
        assertThat(report.eventsDeleted()).isZero();
        verify(deletionOutbox, never()).record(any());
    }

    @Test
    void erase_shouldSkipTheEvent_whenItVanishedOrChangedHandsMeanwhile() {
        organizes(upcoming("e1", USER, "lea"), upcoming("e2", USER, "hugo"));
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.empty());
        when(eventRepository.handOver("e2", USER, "hugo")).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.empty());
        Event changedHands = upcoming("e2", "hugo");
        changedHands.setOrganizerId("hugo");
        when(eventRepository.findById("e2")).thenReturn(Mono.just(changedHands));

        ErasureReport report = erase();

        assertThat(report.eventsHandedOver()).isZero();
        verify(markers).markErased(USER, NOW);
    }

    @Test
    void erase_shouldFailWithErasureIncomplete_afterThreeMissedWrites() {
        Event event = upcoming("e1", USER, "lea");
        organizes(event);
        when(eventRepository.handOver("e1", USER, "lea")).thenReturn(Mono.empty());
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event));

        StepVerifier.create(service.erase(USER, DELETED_AT))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.ERASURE_INCOMPLETE))
                .verify();
        verify(eventRepository, times(AccountErasureService.MAX_RELEASE_ATTEMPTS))
                .handOver("e1", USER, "lea");
        verify(markers, never()).markErased(anyString(), any());
    }

    // --- Participations, answers, receipts ---------------------------------------------------------

    @Test
    void erase_shouldLeaveUpcomingEventsAndClearUpcomingResponsesAtTheDeletionTime() {
        when(eventRepository.leaveUpcoming(USER, DELETED_AT)).thenReturn(Mono.just(3L));
        when(eventRepository.clearUpcomingResponses(USER, DELETED_AT)).thenReturn(Mono.just(2L));

        ErasureReport report = erase();

        assertThat(report.eventsLeft()).isEqualTo(3);
        assertThat(report.responsesCleared()).isEqualTo(2);
    }

    private static Expense receiptOf(String id, String key) {
        return new Expense(id, "e9", "Courses", 1000, "EUR", USER, USER, ExpenseKind.EXPENSE, SplitMode.EQUAL,
                List.of(new Share(USER, 0), new Share("lea", 0)), DELETED_AT, key, USER, 1L);
    }

    @Test
    void erase_shouldDropTheReceiptsTheUserRecorded_objectFirst() {
        when(expenseRepository.findWithReceiptRecordedBy(USER)).thenReturn(Flux.just(receiptOf("x1", "events/e9/receipts/x1-a.jpg")));
        when(mediaStore.delete("events/e9/receipts/x1-a.jpg")).thenReturn(Mono.empty());
        when(expenseRepository.clearReceipt("e9", "x1", "events/e9/receipts/x1-a.jpg")).thenReturn(Mono.just(true));

        assertThat(erase().receiptsDropped()).isEqualTo(1);

        InOrder order = inOrder(mediaStore, expenseRepository);
        order.verify(mediaStore).delete("events/e9/receipts/x1-a.jpg");
        order.verify(expenseRepository).clearReceipt("e9", "x1", "events/e9/receipts/x1-a.jpg");
    }

    @Test
    void erase_shouldNotCountAReceiptReplacedMeanwhile() {
        when(expenseRepository.findWithReceiptRecordedBy(USER)).thenReturn(Flux.just(receiptOf("x1", "events/e9/receipts/x1-a.jpg")));
        when(mediaStore.delete("events/e9/receipts/x1-a.jpg")).thenReturn(Mono.empty());
        when(expenseRepository.clearReceipt("e9", "x1", "events/e9/receipts/x1-a.jpg")).thenReturn(Mono.just(false));

        assertThat(erase().receiptsDropped()).isZero();
    }

    @Test
    void erase_shouldKeepTheReceiptKeyAndNotMarkErased_whenTheObjectCannotBeDeleted() {
        when(expenseRepository.findWithReceiptRecordedBy(USER)).thenReturn(Flux.just(receiptOf("x1", "events/e9/receipts/x1-a.jpg")));
        when(mediaStore.delete("events/e9/receipts/x1-a.jpg"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.MEDIA_UNAVAILABLE)));

        StepVerifier.create(service.erase(USER, DELETED_AT))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.MEDIA_UNAVAILABLE))
                .verify();
        verify(expenseRepository, never()).clearReceipt(anyString(), anyString(), anyString());
        verify(markers, never()).markErased(anyString(), any());
    }

    // --- Completion -------------------------------------------------------------------------------

    @Test
    void erase_shouldMarkTheUserErasedLast() {
        erase();

        InOrder order = inOrder(eventRepository, expenseRepository, markers);
        order.verify(eventRepository).clearUpcomingResponses(USER, DELETED_AT);
        order.verify(expenseRepository).findWithReceiptRecordedBy(USER);
        order.verify(markers).markErased(USER, NOW);
    }

    @Test
    void erase_shouldNotMarkErased_whenAStepFails() {
        when(eventRepository.leaveUpcoming(USER, DELETED_AT))
                .thenReturn(Mono.error(new EventException(EventErrorCode.STORE_UNAVAILABLE)));

        StepVerifier.create(service.erase(USER, DELETED_AT))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.STORE_UNAVAILABLE))
                .verify();
        verify(markers, never()).markErased(anyString(), any());
    }

    @Test
    void erase_shouldFail_whenTheMarkerCannotBeWritten() {
        when(markers.markDeleted(USER, DELETED_AT)).thenReturn(Mono.error(new EventException(EventErrorCode.STORE_UNAVAILABLE)));

        StepVerifier.create(service.erase(USER, DELETED_AT))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.STORE_UNAVAILABLE))
                .verify();
        verifyNoInteractions(eventRepository, pollUpdates);
    }

    @Test
    void erase_shouldReportZeroChanges_whenReplayedAfterCompletion() {
        StepVerifier.create(service.erase(USER, DELETED_AT)).expectNext(ErasureReport.NONE).verifyComplete();

        verify(markers).markErased(eq(USER), eq(NOW));
    }
}
