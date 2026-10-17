package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.CalendarLinkResponse;
import com.calendar.events.domain.services.CalendarExportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** An event into the viewer's own calendar app: a short-lived link, then the .ics behind it. */
@RestController
public class CalendarController {

    static final MediaType TEXT_CALENDAR = new MediaType("text", "calendar", StandardCharsets.UTF_8);
    /** An origin as browsers send it: scheme, host, optional port, nothing else. */
    private static final Pattern ORIGIN = Pattern.compile("^https?://[A-Za-z0-9.-]+(:\\d{1,5})?$");

    private final CalendarExportService calendarExportService;

    public CalendarController(CalendarExportService calendarExportService) {
        this.calendarExportService = calendarExportService;
    }

    /** A link to the event's .ics; the browser's Origin names the web front the event's page is on. */
    @PostMapping("/events/{id}/calendar-link")
    public Mono<CalendarLinkResponse> issueLink(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                                @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin) {
        String userId = jwt.getClaimAsString("businessId");
        return calendarExportService.issueLink(id, userId, webOrigin(origin))
                .map(link -> new CalendarLinkResponse(link.token(), link.expiresAt()));
    }

    /** Opened by the calendar app without a token: served inline, so Apple's « add to calendar » sheet shows. */
    @GetMapping("/public/calendar/{token}.ics")
    public Mono<ResponseEntity<String>> ics(@PathVariable String token) {
        return calendarExportService.icsFor(token)
                .map(ics -> ResponseEntity.ok()
                        .contentType(TEXT_CALENDAR)
                        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("wely.ics").build().toString())
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(ics));
    }

    /** The origin when it is one, else null: the .ics then has no way back to the page. */
    static String webOrigin(String origin) {
        return origin != null && ORIGIN.matcher(origin).matches() ? origin : null;
    }
}
