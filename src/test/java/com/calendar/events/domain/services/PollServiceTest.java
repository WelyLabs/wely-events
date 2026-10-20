package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.CommunityDirectory;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PollServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final PollSlot THURSDAY = new PollSlot("thu", Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"));
    private static final PollSlot FRIDAY = new PollSlot("fri", Instant.parse("2026-10-17T17:00:00Z"), Instant.parse("2026-10-17T19:00:00Z"));

    @Mock
    private EventRepository eventRepository;
    @Mock
    private SocialGraph socialGraph;
    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private MediaStore mediaStore;
    @Mock
    private PollUpdates pollUpdates;
    @Mock
    private DeletedUserMarkers markers;

    private PollService pollService;

    @BeforeEach
    void setUp() {
        AccountGuard accountGuard = new AccountGuard(markers);
        EventService eventService = new EventService(eventRepository, socialGraph, expenseRepository,
                new PictureService(mediaStore), mock(EventDeletionOutbox.class), mock(CommunityDirectory.class),
                accountGuard, Clock.fixed(NOW, ZoneOffset.UTC));
        pollService = new PollService(eventRepository, eventService, pollUpdates, accountGuard);
        // The birthday and hidden-from filter is the database's (see the adapter's integration test):
        // here an event reached by an API caller is whatever findById holds.
        lenient().when(eventRepository.findShownTo(anyString(), anyString()))
                .thenAnswer(call -> eventRepository.findById(call.<String>getArgument(0)));
        lenient().when(markers.isDeleted(anyString())).thenReturn(Mono.just(false));
        lenient().when(markers.deletedAmong(any())).thenReturn(Mono.just(Set.of()));
    }

    private static Poll openPoll(Map<String, Map<String, PollAnswer>> ballots) {
        return new Poll(PollStatus.OPEN, List.of(THURSDAY, FRIDAY), ballots, null);
    }

    private static Event polling(Visibility visibility, Poll poll) {
        return Event.builder().id("e1").organizerId("org").visibility(visibility)
                .startDate(THURSDAY.start()).endDate(THURSDAY.end())
                .participantIds(new ArrayList<>()).poll(poll).build();
    }

    private static boolean hasCode(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException e && e.getErrorCode() == code;
    }

    private static boolean hasCode(Throwable error, TechnicalErrorCode code) {
        return error instanceof TechnicalException e && e.getErrorCode() == code;
    }

    @Test
    void vote_shouldCastTheBallotAndPublishTheNewState() {
        Poll after = openPoll(Map.of("lea", Map.of("thu", PollAnswer.YES)));
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));
        when(eventRepository.castBallot("e1", "lea", Map.of("thu", PollAnswer.YES)))
                .thenReturn(Mono.just(polling(Visibility.PUBLIC, after)));

        StepVerifier.create(pollService.vote("e1", "lea", Map.of("thu", PollAnswer.YES)))
                .expectNext(after)
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(pollUpdates).publish("e1", after);
    }

    @Test
    void vote_shouldReplaceTheWholeBallot_byHandingItOverAsIs() {
        // The repository replaces the voter's ballot; the service never merges it with the old one.
        Poll before = openPoll(Map.of("lea", Map.of("thu", PollAnswer.YES, "fri", PollAnswer.NO)));
        Poll after = openPoll(Map.of("lea", Map.of("fri", PollAnswer.MAYBE)));
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, before)));
        when(eventRepository.castBallot("e1", "lea", Map.of("fri", PollAnswer.MAYBE)))
                .thenReturn(Mono.just(polling(Visibility.PUBLIC, after)));

        StepVerifier.create(pollService.vote("e1", "lea", Map.of("fri", PollAnswer.MAYBE)))
                .assertNext(poll -> assertThat(poll.ballots().get("lea")).containsExactly(Map.entry("fri", PollAnswer.MAYBE)))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void vote_shouldAnswerNotFound_whenTheVoterCannotSeeTheEvent() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PRIVATE, openPoll(Map.of()))));

        StepVerifier.create(pollService.vote("e1", "stranger", Map.of("thu", PollAnswer.YES)))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).castBallot(anyString(), anyString(), any());
        verifyNoInteractions(pollUpdates);
    }

    @Test
    void vote_shouldCheckFriendship_forAFriendsOnlyEvent() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.FRIENDS, openPoll(Map.of()))));
        when(socialGraph.friendIdsOf("lea")).thenReturn(Mono.just(Set.of("org")));
        when(eventRepository.castBallot("e1", "lea", Map.of())).thenReturn(Mono.just(polling(Visibility.FRIENDS, openPoll(Map.of()))));

        StepVerifier.create(pollService.vote("e1", "lea", Map.of())).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void vote_shouldAnswerPollClosed_whenTheDateIsAlreadyFixed() {
        Poll closed = new Poll(PollStatus.CLOSED, List.of(THURSDAY, FRIDAY), Map.of(), "thu");
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, closed)));

        StepVerifier.create(pollService.vote("e1", "lea", Map.of("thu", PollAnswer.YES)))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_CLOSED))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void vote_shouldAnswerPollClosed_whenTheEventHasNoPoll() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, null)));

        StepVerifier.create(pollService.vote("e1", "lea", Map.of()))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_CLOSED))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void vote_shouldAnswerPollClosed_whenFixedBetweenTheReadAndTheWrite() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));
        when(eventRepository.castBallot("e1", "lea", Map.of("thu", PollAnswer.YES))).thenReturn(Mono.empty());

        StepVerifier.create(pollService.vote("e1", "lea", Map.of("thu", PollAnswer.YES)))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_CLOSED))
                .verify(Duration.ofSeconds(10));
        verifyNoInteractions(pollUpdates);
    }

    @Test
    void vote_shouldAnswerSlotUnknown_forAnAnswerToASlotNotProposed() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));

        StepVerifier.create(pollService.vote("e1", "lea", Map.of("thu", PollAnswer.YES, "sun", PollAnswer.NO)))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_SLOT_UNKNOWN))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).castBallot(anyString(), anyString(), any());
    }

    @Test
    void fix_shouldHandTheSlotAndItsYesVotersToTheRepository_thenPublish() {
        Poll poll = openPoll(Map.of(
                "lea", Map.of("fri", PollAnswer.YES),
                "hugo", Map.of("fri", PollAnswer.MAYBE),
                "zoe", Map.of("thu", PollAnswer.YES)));
        Poll closed = new Poll(PollStatus.CLOSED, poll.slots(), poll.ballots(), "fri");
        Event fixed = polling(Visibility.PRIVATE, closed);
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PRIVATE, poll)));
        when(eventRepository.fixPollDate("e1", "org", FRIDAY, List.of("lea"))).thenReturn(Mono.just(fixed));

        StepVerifier.create(pollService.fix("e1", "org", "fri"))
                .expectNext(fixed)
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(pollUpdates).publish("e1", closed);
    }

    @Test
    void fix_shouldNotSubscribeYesVotersWhoseAccountIsDeleted() {
        Poll poll = openPoll(Map.of(
                "lea", Map.of("fri", PollAnswer.YES),
                "gone", Map.of("fri", PollAnswer.YES)));
        Event fixed = polling(Visibility.PRIVATE, new Poll(PollStatus.CLOSED, poll.slots(), poll.ballots(), "fri"));
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PRIVATE, poll)));
        when(markers.deletedAmong(any())).thenReturn(Mono.just(Set.of("gone")));
        when(eventRepository.fixPollDate("e1", "org", FRIDAY, List.of("lea"))).thenReturn(Mono.just(fixed));

        StepVerifier.create(pollService.fix("e1", "org", "fri"))
                .expectNext(fixed)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void fix_shouldAnswerTheStoreError_whenTheRegisterCannotBeRead() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PRIVATE,
                openPoll(Map.of("lea", Map.of("fri", PollAnswer.YES))))));
        when(markers.deletedAmong(any())).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE)));

        StepVerifier.create(pollService.fix("e1", "org", "fri"))
                .expectErrorMatches(error -> hasCode(error, TechnicalErrorCode.STORE_UNAVAILABLE))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).fixPollDate(anyString(), anyString(), any(), any());
    }

    @Test
    void fix_shouldBeRefused_forSomeoneOtherThanTheOrganizer() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));

        StepVerifier.create(pollService.fix("e1", "lea", "fri"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_ACCESS_DENIED))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).fixPollDate(anyString(), anyString(), any(), any());
    }

    @Test
    void fix_shouldAnswerNotFound_forSomeoneWhoCannotSeeTheEvent() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PRIVATE, openPoll(Map.of()))));

        StepVerifier.create(pollService.fix("e1", "stranger", "fri"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void fix_shouldAnswerPollClosed_whenAlreadyFixed() {
        Poll closed = new Poll(PollStatus.CLOSED, List.of(THURSDAY, FRIDAY), Map.of(), "thu");
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, closed)));

        StepVerifier.create(pollService.fix("e1", "org", "fri"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_CLOSED))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void fix_shouldAnswerPollClosed_whenAnotherFixWonTheRace() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));
        when(eventRepository.fixPollDate("e1", "org", FRIDAY, List.of())).thenReturn(Mono.empty());

        StepVerifier.create(pollService.fix("e1", "org", "fri"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_CLOSED))
                .verify(Duration.ofSeconds(10));
        verifyNoInteractions(pollUpdates);
    }

    @Test
    void fix_shouldAnswerSlotUnknown_forASlotNotProposed() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));

        StepVerifier.create(pollService.fix("e1", "org", "sun"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_SLOT_UNKNOWN))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void fix_shouldRefuseASlotAlreadyPast() {
        PollSlot gone = new PollSlot("gone", NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)).plus(Duration.ofHours(2)));
        Poll poll = new Poll(PollStatus.OPEN, List.of(gone, THURSDAY), Map.of(), null);
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, poll)));

        StepVerifier.create(pollService.fix("e1", "org", "gone"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_IN_PAST))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).fixPollDate(any(), any(), any(), any());
    }

    @Test
    void getMyPolls_shouldAskTheRepositoryForTheUsersOpenPolls() {
        Event event = polling(Visibility.PUBLIC, openPoll(Map.of()));
        when(eventRepository.findOpenPollsInvolving("lea")).thenReturn(Flux.just(event));

        StepVerifier.create(pollService.getMyPolls("lea")).expectNext(event).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void watch_shouldAnswerNotFound_whenTheViewerCannotSeeTheEvent() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PRIVATE, openPoll(Map.of()))));

        StepVerifier.create(pollService.watch("e1", "stranger"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
        verifyNoInteractions(pollUpdates);
    }

    @Test
    void watch_shouldRefuseADeletedViewer_withoutStreaming() {
        when(markers.isDeleted("gone")).thenReturn(Mono.just(true));

        StepVerifier.create(pollService.watch("e1", "gone"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.ACCOUNT_DELETED))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).findById(anyString());
        verifyNoInteractions(pollUpdates);
    }

    @Test
    void watch_shouldEndTheStream_whenTheViewerIsDeletedBetweenTheCheckAndTheSubscription() {
        List<String> order = new CopyOnWriteArrayList<>();
        when(markers.isDeleted("lea"))
                .thenReturn(Mono.just(false))
                .thenReturn(Mono.fromCallable(() -> order.add("recheck")));
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));
        when(pollUpdates.stream("e1", "lea")).thenReturn(Flux.<Poll>never().doOnSubscribe(subscription -> order.add("live")));

        StepVerifier.create(pollService.watch("e1", "lea").flatMapMany(states -> states))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertThat(order).containsExactly("live", "recheck");
    }

    @Test
    void watch_shouldFailTheStream_whenTheRecheckCannotReadTheRegister() {
        when(markers.isDeleted("lea"))
                .thenReturn(Mono.just(false))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE)));
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, openPoll(Map.of()))));
        when(pollUpdates.stream("e1", "lea")).thenReturn(Flux.never());

        StepVerifier.create(pollService.watch("e1", "lea").flatMapMany(states -> states))
                .expectErrorMatches(error -> hasCode(error, TechnicalErrorCode.STORE_UNAVAILABLE))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void watch_shouldAnswerPollClosed_whenTheEventHasNoPoll() {
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, null)));

        StepVerifier.create(pollService.watch("e1", "lea"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.POLL_CLOSED))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void watch_shouldEmitTheCurrentStateThenUpdates_andCompleteOnceClosed() {
        Poll current = openPoll(Map.of());
        Poll voted = openPoll(Map.of("lea", Map.of("thu", PollAnswer.YES)));
        Poll closed = new Poll(PollStatus.CLOSED, voted.slots(), voted.ballots(), "thu");
        Sinks.Many<Poll> live = Sinks.many().multicast().directBestEffort();
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, current)));
        when(pollUpdates.stream("e1", "lea")).thenReturn(live.asFlux());

        Flux<Poll> states = pollService.watch("e1", "lea").block();

        StepVerifier.create(states)
                .expectNext(current)
                .then(() -> live.tryEmitNext(voted))
                .expectNext(voted)
                .then(() -> live.tryEmitNext(closed))
                .expectNext(closed)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void watch_shouldEndAtOnce_whenThePollIsAlreadyClosed() {
        Poll closed = new Poll(PollStatus.CLOSED, List.of(THURSDAY, FRIDAY), Map.of(), "thu");
        when(eventRepository.findById("e1")).thenReturn(Mono.just(polling(Visibility.PUBLIC, closed)));
        when(pollUpdates.stream("e1", "lea")).thenReturn(Flux.never());

        StepVerifier.create(pollService.watch("e1", "lea").flatMapMany(states -> states))
                .expectNext(closed)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void watch_shouldDropTheReadState_whenALiveUpdateArrivedFirst() {
        Poll stale = openPoll(Map.of());
        Poll newer = openPoll(Map.of("lea", Map.of("thu", PollAnswer.YES)));
        Sinks.One<Event> slowRead = Sinks.one();
        when(eventRepository.findById("e1"))
                .thenReturn(Mono.just(polling(Visibility.PUBLIC, stale)))
                .thenReturn(slowRead.asMono());
        Sinks.Many<Poll> live = Sinks.many().multicast().directBestEffort();
        when(pollUpdates.stream("e1", "lea")).thenReturn(live.asFlux());

        StepVerifier.create(pollService.watch("e1", "lea").flatMapMany(states -> states))
                .then(() -> live.tryEmitNext(newer))
                .expectNext(newer)
                .then(() -> slowRead.tryEmitValue(polling(Visibility.PUBLIC, stale)))
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }
}
