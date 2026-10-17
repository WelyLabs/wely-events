package com.calendar.events.domain.models;

import java.time.Instant;

/**
 * A short-lived, unguessable address of one event's iCalendar file. A calendar app opens it
 * without the viewer's token, so the link stands for the visibility check done when it was issued.
 *
 * @param pageUrl the event's page on the web front the request came from, null when unknown
 */
public record CalendarLink(String token, String eventId, String pageUrl, Instant expiresAt) {

    public boolean isValidAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
