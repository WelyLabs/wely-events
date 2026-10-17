package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.CalendarLink;
import reactor.core.publisher.Mono;

/** The calendar links handed out, kept until they expire; a store failure arrives as STORE_UNAVAILABLE. */
public interface CalendarLinks {

    Mono<CalendarLink> save(CalendarLink link);

    /** The link behind this token, expired or not; empty when there is none. */
    Mono<CalendarLink> findByToken(String token);
}
