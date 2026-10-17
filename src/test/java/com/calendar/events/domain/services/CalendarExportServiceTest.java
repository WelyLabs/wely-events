package com.calendar.events.domain.services;

import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.ports.CalendarLinks;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final Instant START = Instant.parse("2026-10-24T17:30:00Z");

    @Mock
    private EventService eventService;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private CalendarLinks calendarLinks;

    private CalendarExportService service;

    @BeforeEach
    void setUp() {
        service = new CalendarExportService(eventService, eventRepository, calendarLinks, new SecureRandom(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Event event() {
        return Event.builder().id("e1").title("Soirée jeux").organizerId("org")
                .startDate(START).endDate(START.plusSeconds(3 * 3600)).location("Lyon").description("").build();
    }

    private static boolean failsWith(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    @Test
    void issueLink_shouldSaveAFiveMinuteLink_toTheEventsPage() {
        when(eventService.getEventById("e1", "me")).thenReturn(Mono.just(event()));
        when(calendarLinks.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(service.issueLink("e1", "me", "https://web.welylabs.app"))
                .assertNext(link -> {
                    assertThat(link.eventId()).isEqualTo("e1");
                    assertThat(link.pageUrl()).isEqualTo("https://web.welylabs.app/event/e1");
                    assertThat(link.expiresAt()).isEqualTo(NOW.plus(CalendarExportService.LINK_LIFETIME));
                    // 32 random bytes, base64url without padding.
                    assertThat(link.token()).hasSize(43).matches("[A-Za-z0-9_-]+");
                })
                .verifyComplete();
    }

    @Test
    void issueLink_shouldNeverHandOutTheSameTokenTwice() {
        when(eventService.getEventById("e1", "me")).thenReturn(Mono.just(event()));
        when(calendarLinks.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        String first = service.issueLink("e1", "me", null).block().token();
        String second = service.issueLink("e1", "me", null).block().token();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void issueLink_shouldKeepNoPage_withoutAnOrigin() {
        when(eventService.getEventById("e1", "me")).thenReturn(Mono.just(event()));
        when(calendarLinks.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(service.issueLink("e1", "me", null))
                .assertNext(link -> assertThat(link.pageUrl()).isNull())
                .verifyComplete();
    }

    @Test
    void issueLink_shouldRefuse_whileTheDateIsVotedOn() {
        Event polling = event();
        polling.setPoll(Poll.open(List.of(new PollSlot("s1", START, START.plusSeconds(3600)),
                new PollSlot("s2", START.plusSeconds(86_400), START.plusSeconds(90_000))), NOW));
        when(eventService.getEventById("e1", "me")).thenReturn(Mono.just(polling));

        StepVerifier.create(service.issueLink("e1", "me", null))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.DATE_NOT_FIXED))
                .verify();

        verify(calendarLinks, never()).save(any());
    }

    @Test
    void issueLink_shouldPassOnTheVisibilityRefusal() {
        when(eventService.getEventById("e1", "stranger"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)));

        StepVerifier.create(service.issueLink("e1", "stranger", null))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();

        verify(calendarLinks, never()).save(any());
    }

    @Test
    void icsFor_shouldWriteTheEvent_behindAValidLink() {
        when(calendarLinks.findByToken("t"))
                .thenReturn(Mono.just(new CalendarLink("t", "e1", "https://web.welylabs.app/event/e1", NOW.plusSeconds(60))));
        when(eventRepository.findById("e1")).thenReturn(Mono.just(event()));

        StepVerifier.create(service.icsFor("t"))
                .assertNext(ics -> assertThat(ics)
                        .contains("SUMMARY:Soirée jeux")
                        .contains("DTSTAMP:20261017T100000Z")
                        .contains("URL:https://web.welylabs.app/event/e1"))
                .verifyComplete();
    }

    @Test
    void icsFor_shouldRefuse_anExpiredLink() {
        when(calendarLinks.findByToken("t")).thenReturn(Mono.just(new CalendarLink("t", "e1", null, NOW)));

        StepVerifier.create(service.icsFor("t"))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.CALENDAR_LINK_EXPIRED))
                .verify();

        verify(eventRepository, never()).findById(any());
    }

    @Test
    void icsFor_shouldRefuse_anUnknownToken() {
        when(calendarLinks.findByToken("nope")).thenReturn(Mono.empty());

        StepVerifier.create(service.icsFor("nope"))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.CALENDAR_LINK_EXPIRED))
                .verify();
    }

    @Test
    void icsFor_shouldRefuse_onceTheEventIsGone() {
        when(calendarLinks.findByToken("t")).thenReturn(Mono.just(new CalendarLink("t", "e1", null, NOW.plusSeconds(60))));
        when(eventRepository.findById("e1")).thenReturn(Mono.empty());

        StepVerifier.create(service.icsFor("t"))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.CALENDAR_LINK_EXPIRED))
                .verify();
    }

    @Test
    void issueLink_shouldSaveWhatItReturns() {
        when(eventService.getEventById("e1", "me")).thenReturn(Mono.just(event()));
        when(calendarLinks.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        CalendarLink issued = service.issueLink("e1", "me", null).block();

        ArgumentCaptor<CalendarLink> saved = ArgumentCaptor.forClass(CalendarLink.class);
        verify(calendarLinks).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(issued);
    }
}
