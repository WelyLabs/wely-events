package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.CalendarLinkResponse;
import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.domain.services.CalendarExportService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarControllerTest {

    private static final Instant EXPIRY = Instant.parse("2026-10-17T10:05:00Z");

    @Mock
    private CalendarExportService calendarExportService;

    @Mock
    private Jwt jwt;

    private CalendarController controller;

    @BeforeEach
    void setUp() {
        controller = new CalendarController(calendarExportService);
    }

    @Test
    void issueLink_shouldIssueForTheTokenUser_fromTheBrowsersOrigin() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(calendarExportService.issueLink("e1", "me", "https://web.welylabs.app"))
                .thenReturn(Mono.just(new CalendarLink("tok", "e1", "https://web.welylabs.app/event/e1", EXPIRY)));

        StepVerifier.create(controller.issueLink("e1", jwt, "https://web.welylabs.app"))
                .expectNext(new CalendarLinkResponse("tok", EXPIRY))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void issueLink_shouldDropAnOriginThatIsNotOne() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(calendarExportService.issueLink("e1", "me", null))
                .thenReturn(Mono.just(new CalendarLink("tok", "e1", null, EXPIRY)));

        StepVerifier.create(controller.issueLink("e1", jwt, "https://evil.example/phish?x=1"))
                .expectNext(new CalendarLinkResponse("tok", EXPIRY))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void ics_shouldServeTheFileInline_asTextCalendar() {
        when(calendarExportService.icsFor("tok")).thenReturn(Mono.just("BEGIN:VCALENDAR\r\n"));

        StepVerifier.create(controller.ics("tok"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getHeaders().getContentType()).isEqualTo(CalendarController.TEXT_CALENDAR);
                    assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                            .isEqualTo("inline; filename=\"wely.ics\"");
                    assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
                    assertThat(response.getBody()).isEqualTo("BEGIN:VCALENDAR\r\n");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void ics_shouldPassOnAnExpiredLink() {
        when(calendarExportService.icsFor("old"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.CALENDAR_LINK_EXPIRED)));

        StepVerifier.create(controller.ics("old"))
                .expectErrorMatches(error -> error instanceof BusinessException e
                        && e.getErrorCode() == BusinessErrorCode.CALENDAR_LINK_EXPIRED)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void webOrigin_shouldKeepOnlySchemeHostAndPort() {
        assertThat(CalendarController.webOrigin("https://web.welylabs.app")).isEqualTo("https://web.welylabs.app");
        assertThat(CalendarController.webOrigin("http://localhost:4200")).isEqualTo("http://localhost:4200");
        assertThat(CalendarController.webOrigin(null)).isNull();
        assertThat(CalendarController.webOrigin("null")).isNull();
        assertThat(CalendarController.webOrigin("javascript:alert(1)")).isNull();
        assertThat(CalendarController.webOrigin("https://web.welylabs.app/path")).isNull();
    }
}
