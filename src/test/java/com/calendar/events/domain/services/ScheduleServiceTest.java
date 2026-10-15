package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** Thursday 14:00 – 16:00 in Paris. */
    private static final Instant BOWLING_START = Instant.parse("2026-10-16T12:00:00Z");
    private static final Instant BOWLING_END = Instant.parse("2026-10-16T14:00:00Z");
    private static final TimeRange AN_HOUR_LATER = new TimeRange(BOWLING_START.plus(Duration.ofHours(1)),
            BOWLING_END.plus(Duration.ofHours(1)));

    @Mock
    private EventRepository eventRepository;
    @Mock
    private SocialGraph socialGraph;
    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private MediaStore mediaStore;

    private ScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        EventService eventService = new EventService(eventRepository, socialGraph, expenseRepository,
                new PictureService(mediaStore), mock(EventDeletionOutbox.class), clock);
        scheduleService = new ScheduleService(eventRepository, eventService, clock, PARIS);
    }

    private static Event bowling(String organizer, String... participants) {
        return Event.builder().id("bowling").title("Bowling").organizerId(organizer).visibility(Visibility.PUBLIC)
                .startDate(BOWLING_START).endDate(BOWLING_END)
                .participantIds(new ArrayList<>(List.of(participants))).build();
    }

    private static Event appointment() {
        return Event.builder().id("doctor").title("Médecin").organizerId("me")
                .startDate(BOWLING_START).endDate(BOWLING_START.plus(Duration.ofHours(1)))
                .participantIds(new ArrayList<>()).build();
    }

    private static Poll openPoll() {
        PollSlot slot = new PollSlot("thu", BOWLING_START, BOWLING_END);
        return new Poll(PollStatus.OPEN, List.of(slot, new PollSlot("fri", BOWLING_START.plus(Duration.ofDays(1)),
                BOWLING_END.plus(Duration.ofDays(1)))), null, null);
    }

    private static boolean hasCode(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    @Test
    void conflictsWith_shouldReturnTheUsersOverlappingEvents_leavingOutTheExcludedOne() {
        Event bowling = bowling("me");
        when(eventRepository.findOccupying(Set.of("me"), BOWLING_START, BOWLING_END))
                .thenReturn(Flux.just(bowling, appointment()));

        StepVerifier.create(scheduleService.conflictsWith("me", new TimeRange(BOWLING_START, BOWLING_END), "doctor"))
                .expectNext(bowling)
                .verifyComplete();
    }

    @Test
    void conflictsWith_shouldKeepEverything_whenNothingIsExcluded() {
        when(eventRepository.findOccupying(Set.of("me"), BOWLING_START, BOWLING_END))
                .thenReturn(Flux.just(appointment()));

        StepVerifier.create(scheduleService.conflictsWith("me", new TimeRange(BOWLING_START, BOWLING_END), null))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void conflictsWith_shouldRefuseARangeWiderThanAMonthGrid() {
        TimeRange tooWide = new TimeRange(BOWLING_START, BOWLING_START.plus(Duration.ofDays(63)));

        StepVerifier.create(scheduleService.conflictsWith("me", tooWide, null))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_INVALID))
                .verify();
        verifyNoInteractions(eventRepository);
    }

    @Test
    void conflictsOf_shouldCheckAVisibleEventAgainstTheViewersAgenda() {
        Event bowling = bowling("lea", "hugo");
        Event doctor = appointment();
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(bowling));
        when(eventRepository.findOccupying(Set.of("me"), BOWLING_START, BOWLING_END)).thenReturn(Flux.just(doctor));

        StepVerifier.create(scheduleService.conflictsOf("bowling", "me")).expectNext(doctor).verifyComplete();
    }

    @Test
    void conflictsOf_shouldTakeAnEventWithoutAnEndForItsDefaultDuration() {
        Event noEnd = bowling("me");
        noEnd.setEndDate(null);
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(noEnd));
        when(eventRepository.findOccupying(Set.of("me"), BOWLING_START, BOWLING_START.plus(Event.DEFAULT_DURATION)))
                .thenReturn(Flux.just(noEnd));

        // The event itself is never its own conflict.
        StepVerifier.create(scheduleService.conflictsOf("bowling", "me")).verifyComplete();
    }

    @Test
    void conflictsOf_shouldFindNothing_forAnOpenPoll() {
        Event polling = bowling("me");
        polling.setPoll(openPoll());
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(polling));

        StepVerifier.create(scheduleService.conflictsOf("bowling", "me")).verifyComplete();
        verify(eventRepository, never()).findOccupying(any(), any(), any());
    }

    @Test
    void conflictsOf_shouldAnswerNotFound_forAnEventTheViewerMayNotSee() {
        Event hidden = bowling("lea");
        hidden.setVisibility(Visibility.PRIVATE);
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(hidden));

        StepVerifier.create(scheduleService.conflictsOf("bowling", "me"))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void proposalsFor_shouldAvoidEveryoneTakingPart_butNotTheEventItself() {
        Event bowling = bowling("me", "lea");
        // Léa is busy 14:30 – 15:30 in Paris, after the appointment: the first free move is +1 h 30.
        Event leaBusy = Event.builder().id("lea-busy").startDate(Instant.parse("2026-10-16T12:30:00Z"))
                .endDate(Instant.parse("2026-10-16T13:30:00Z")).build();
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(bowling));
        when(eventRepository.findOccupying(any(), any(), any())).thenReturn(Flux.just(bowling, appointment(), leaBusy));

        List<TimeRange> proposals = scheduleService.proposalsFor("bowling", "me").collectList().block();

        assertThat(proposals).hasSize(ScheduleService.MAX_PROPOSALS)
                .allMatch(proposal -> !proposal.start().isBefore(Instant.parse("2026-10-16T13:30:00Z"))
                        || !proposal.end().isAfter(BOWLING_START))
                .allMatch(proposal -> proposal.duration().equals(Duration.ofHours(2)));
        assertThat(proposals.getFirst()).isEqualTo(new TimeRange(Instant.parse("2026-10-16T13:30:00Z"),
                Instant.parse("2026-10-16T15:30:00Z")));
        verify(eventRepository).findOccupying(Set.of("me", "lea"), BOWLING_START.minus(ScheduleService.LOOK_BEHIND),
                BOWLING_START.plus(ScheduleService.LOOK_AHEAD).plus(Duration.ofHours(2)));
    }

    @Test
    void proposalsFor_shouldNeverProposeThePast() {
        Event tomorrowMorning = bowling("me");
        tomorrowMorning.setStartDate(NOW.plus(Duration.ofHours(2)));
        tomorrowMorning.setEndDate(NOW.plus(Duration.ofHours(3)));
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(tomorrowMorning));
        when(eventRepository.findOccupying(any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(scheduleService.proposalsFor("bowling", "me"))
                .thenConsumeWhile(proposal -> !proposal.start().isBefore(NOW))
                .verifyComplete();
        verify(eventRepository).findOccupying(Set.of("me"), NOW,
                tomorrowMorning.getStartDate().plus(ScheduleService.LOOK_AHEAD).plus(Duration.ofHours(1)));
    }

    @Test
    void proposalsFor_shouldBeRefused_toSomeoneOtherThanTheOrganizer() {
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(bowling("lea", "me")));

        StepVerifier.create(scheduleService.proposalsFor("bowling", "me"))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
        verify(eventRepository, never()).findOccupying(any(), any(), any());
    }

    @Test
    void proposalsFor_shouldBeRefused_whileTheDatePollIsOpen() {
        Event polling = bowling("me");
        polling.setPoll(openPoll());
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(polling));

        StepVerifier.create(scheduleService.proposalsFor("bowling", "me"))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_LOCKED))
                .verify();
    }

    @Test
    void reschedule_shouldMoveASharedEvent_andAskForTheAnnouncement() {
        Event bowling = bowling("me", "me", "lea");
        Event moved = bowling("me", "me", "lea");
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(bowling));
        when(eventRepository.reschedule(bowling, AN_HOUR_LATER, true)).thenReturn(Mono.just(moved));

        StepVerifier.create(scheduleService.reschedule("bowling", "me", AN_HOUR_LATER)).expectNext(moved).verifyComplete();
    }

    @Test
    void reschedule_shouldNotAnnounce_whenTheOrganizerIsAlone() {
        Event alone = bowling("me", "me");
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(alone));
        when(eventRepository.reschedule(alone, AN_HOUR_LATER, false)).thenReturn(Mono.just(alone));

        StepVerifier.create(scheduleService.reschedule("bowling", "me", AN_HOUR_LATER)).expectNext(alone).verifyComplete();
    }

    @Test
    void reschedule_shouldWriteNothing_whenTheDatesAreUnchanged() {
        Event bowling = bowling("me", "lea");
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(bowling));

        StepVerifier.create(scheduleService.reschedule("bowling", "me", new TimeRange(BOWLING_START, BOWLING_END)))
                .expectNext(bowling)
                .verifyComplete();
        verify(eventRepository, never()).reschedule(any(), any(), anyBoolean());
    }

    @Test
    void reschedule_shouldReportAConflict_whenTheDateChangedMeanwhile() {
        Event bowling = bowling("me", "lea");
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(bowling));
        when(eventRepository.reschedule(bowling, AN_HOUR_LATER, true)).thenReturn(Mono.empty());

        StepVerifier.create(scheduleService.reschedule("bowling", "me", AN_HOUR_LATER))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_CHANGED))
                .verify();
    }

    @Test
    void reschedule_shouldBeRefused_toAParticipant() {
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(bowling("lea", "me")));

        StepVerifier.create(scheduleService.reschedule("bowling", "me", AN_HOUR_LATER))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
        verify(eventRepository, never()).reschedule(any(), any(), anyBoolean());
    }

    @Test
    void reschedule_shouldBeRefused_whileTheDatePollIsOpen() {
        Event polling = bowling("me");
        polling.setPoll(openPoll());
        when(eventRepository.findById("bowling")).thenReturn(Mono.just(polling));

        StepVerifier.create(scheduleService.reschedule("bowling", "me", AN_HOUR_LATER))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_LOCKED))
                .verify();
    }

    @Test
    void reschedule_shouldRefuseThePast_orARangeWiderThanAMonthGrid() {
        TimeRange past = new TimeRange(NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)));
        TimeRange tooWide = new TimeRange(BOWLING_START, BOWLING_START.plus(Duration.ofDays(63)));

        StepVerifier.create(scheduleService.reschedule("bowling", "me", past))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_INVALID))
                .verify();
        StepVerifier.create(scheduleService.reschedule("bowling", "me", tooWide))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_INVALID))
                .verify();
        verifyNoInteractions(eventRepository);
    }

    @Test
    void reschedule_shouldAnswerNotFound_forAnEventThatDoesNotExist() {
        when(eventRepository.findById("bowling")).thenReturn(Mono.empty());

        StepVerifier.create(scheduleService.reschedule("bowling", "me", AN_HOUR_LATER))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }
}
