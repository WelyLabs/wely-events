package com.calendar.events.domain.models;

import java.time.Instant;

/**
 * One event of a friend's calendar as the viewer is allowed to see it: always when, then the
 * rest only if disclosed. Undisclosed fields are null.
 */
public record CalendarEntry(
        Instant start,
        Instant end,
        String eventId,
        String title,
        String location,
        String description,
        boolean allDay
) {

    public static CalendarEntry busy(Instant start, Instant end, boolean allDay) {
        return new CalendarEntry(start, end, null, null, null, null, allDay);
    }
}
