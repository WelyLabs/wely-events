package com.calendar.events.domain.services;

import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.domain.ports.CalendarLinks;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

/**
 * An event into the viewer's own calendar app. The app opens a plain address, without the
 * viewer's token: the viewer first asks for a link (checked like reading the event), then the
 * app reads the .ics through it for a few minutes. Apple's « add to calendar » sheet only shows
 * for a file served this way, as {@code text/calendar}, not for one built in the page.
 */
public class CalendarExportService {

    /** Long enough to open it, short enough that a link copied around soon stops working. */
    static final Duration LINK_LIFETIME = Duration.ofMinutes(5);
    private static final int TOKEN_BYTES = 32;

    private final EventService eventService;
    private final EventRepository eventRepository;
    private final CalendarLinks calendarLinks;
    private final SecureRandom random;
    private final Clock clock;

    public CalendarExportService(EventService eventService, EventRepository eventRepository,
                                 CalendarLinks calendarLinks, SecureRandom random, Clock clock) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.calendarLinks = calendarLinks;
        this.random = random;
        this.clock = clock;
    }

    /**
     * A link to the event's .ics, for a viewer who may see it and once its date is fixed.
     *
     * @param webOrigin the web front the viewer is on, for the way back to the event; may be null
     */
    public Mono<CalendarLink> issueLink(String eventId, String viewerId, String webOrigin) {
        return eventService.getEventById(eventId, viewerId)
                .flatMap(event -> event.isPolling()
                        ? Mono.error(new EventException(EventErrorCode.DATE_NOT_FIXED))
                        : calendarLinks.save(new CalendarLink(newToken(), event.getId(), pageUrl(webOrigin, event.getId()),
                                clock.instant().plus(LINK_LIFETIME))));
    }

    /** The event's .ics behind a link still valid; CALENDAR_LINK_EXPIRED otherwise, or once the event is gone. */
    public Mono<String> icsFor(String token) {
        return calendarLinks.findByToken(token)
                .filter(link -> link.isValidAt(clock.instant()))
                .flatMap(link -> eventRepository.findById(link.eventId())
                        .map(event -> IcsWriter.write(event, link.pageUrl(), clock.instant())))
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.CALENDAR_LINK_EXPIRED)));
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String pageUrl(String webOrigin, String eventId) {
        return webOrigin == null ? null : webOrigin + "/event/" + eventId;
    }
}
