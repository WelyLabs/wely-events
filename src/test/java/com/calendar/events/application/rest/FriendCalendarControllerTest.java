package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.CalendarEntryResponse;
import com.calendar.events.application.dtos.CircleEntryResponse;
import com.calendar.events.domain.models.CircleEntry;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.services.FriendCalendarService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendCalendarControllerTest {

    private static final Instant FROM = Instant.parse("2026-10-12T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-19T00:00:00Z");

    @Mock
    private FriendCalendarService service;
    @Mock
    private Jwt jwt;

    private FriendCalendarController controller;

    @BeforeEach
    void setUp() {
        controller = new FriendCalendarController(service, Mappers.getMapper(EventRestMapper.class));
    }

    @Test
    void getFriendCalendar_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("alice");
        when(service.getFriendCalendar("alice", "bob", new TimeRange(FROM, TO)))
                .thenReturn(Flux.just(CalendarEntry.busy(FROM, FROM.plusSeconds(3600), false)));

        StepVerifier.create(controller.getFriendCalendar("bob", FROM, TO, jwt))
                .expectNext(new CalendarEntryResponse(FROM, FROM.plusSeconds(3600), null, null, null, null, false))
                .verifyComplete();
    }

    @Test
    void getFriendCalendar_shouldRejectAWindowEndingBeforeItStarts() {
        StepVerifier.create(controller.getFriendCalendar("bob", TO, FROM, jwt))
                .expectErrorMatches(e -> e instanceof BusinessException ex
                        && ex.getErrorCode() == BusinessErrorCode.INVALID_CALENDAR_REQUEST)
                .verify();
        verifyNoInteractions(service);
    }

    @Test
    void getCircleCalendar_shouldAskOnBehalfOfTheCaller_andSayWhoseEachEntryIs() {
        when(jwt.getClaimAsString("businessId")).thenReturn("alice");
        CalendarEntry dinner = new CalendarEntry(FROM, FROM.plusSeconds(3600), "e1", "Dinner", "Lyon", "…", false);
        when(service.getCircleCalendar("alice", new TimeRange(FROM, TO)))
                .thenReturn(Flux.just(new CircleEntry("bob", dinner)));

        StepVerifier.create(controller.getCircleCalendar(FROM, TO, jwt))
                .expectNext(new CircleEntryResponse("bob", FROM, FROM.plusSeconds(3600), false, "e1", "Dinner", "Lyon", "…"))
                .verifyComplete();
    }

    @Test
    void getCircleCalendar_shouldRejectAWindowEndingBeforeItStarts() {
        StepVerifier.create(controller.getCircleCalendar(TO, FROM, jwt))
                .expectErrorMatches(e -> e instanceof BusinessException ex
                        && ex.getErrorCode() == BusinessErrorCode.INVALID_CIRCLE_REQUEST)
                .verify();
        verifyNoInteractions(service);
    }
}
