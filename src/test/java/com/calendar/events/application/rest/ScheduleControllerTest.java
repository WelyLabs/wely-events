package com.calendar.events.application.rest;

import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.application.dtos.ConflictCheckRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.ScheduleRequest;
import com.calendar.events.application.dtos.TimeRangeResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.application.mappers.FreeSlotRestMapperImpl;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.ScheduleService;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleControllerTest {

    private static final Instant START = Instant.parse("2026-10-16T12:00:00Z");
    private static final Instant END = Instant.parse("2026-10-16T14:00:00Z");

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private ScheduleService scheduleService;
    @Mock
    private EventRestMapper eventMapper;
    @Mock
    private MediaStore mediaStore;
    @Mock
    private Jwt jwt;

    private ScheduleController controller;

    @BeforeEach
    void setUp() {
        lenient().when(friendshipService.viewerFriends(anyString())).thenReturn(Mono.just(ViewerFriends.NONE));
        controller = new ScheduleController(scheduleService, new PictureService(mediaStore), friendshipService, eventMapper,
                new FreeSlotRestMapperImpl());
    }

    private static boolean hasCode(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    @Test
    void checkConflicts_shouldCheckTheTimeAgainstTheCallersAgenda() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        Event doctor = Event.builder().id("doctor").build();
        EventResponse response = EventResponse.builder().id("doctor").build();
        when(scheduleService.conflictsWith("me", new TimeRange(START, END), "bowling")).thenReturn(Flux.just(doctor));
        when(eventMapper.toResponse(doctor, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.checkConflicts(new ConflictCheckRequest(START, END, "bowling"), jwt))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void checkConflicts_shouldRejectATimeThatEndsBeforeItStarts() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");

        StepVerifier.create(controller.checkConflicts(new ConflictCheckRequest(END, START, null), jwt))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_INVALID))
                .verify();
        verify(scheduleService, never()).conflictsWith(any(), any(), any());
    }

    @Test
    void getConflicts_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        Event doctor = Event.builder().id("doctor").build();
        EventResponse response = EventResponse.builder().id("doctor").build();
        when(scheduleService.conflictsOf("bowling", "me")).thenReturn(Flux.just(doctor));
        when(eventMapper.toResponse(doctor, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.getConflicts("bowling", jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void getProposals_shouldAnswerTheRanges() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(scheduleService.proposalsFor("bowling", "me")).thenReturn(Flux.just(new TimeRange(START, END)));

        StepVerifier.create(controller.getProposals("bowling", jwt))
                .expectNext(new TimeRangeResponse(START, END))
                .verifyComplete();
    }

    @Test
    void reschedule_shouldMoveTheEventOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        Event moved = Event.builder().id("bowling").build();
        EventResponse response = EventResponse.builder().id("bowling").build();
        when(scheduleService.reschedule("bowling", "me", new TimeRange(START, END))).thenReturn(Mono.just(moved));
        when(eventMapper.toResponse(moved, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.reschedule("bowling", new ScheduleRequest(START, END), jwt))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void reschedule_shouldRejectATimeThatEndsBeforeItStarts() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");

        StepVerifier.create(controller.reschedule("bowling", new ScheduleRequest(START, START), jwt))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.SCHEDULE_INVALID))
                .verify();
        verify(scheduleService, never()).reschedule(any(), any(), any());
    }
}
