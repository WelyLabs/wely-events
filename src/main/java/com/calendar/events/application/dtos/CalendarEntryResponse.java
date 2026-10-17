package com.calendar.events.application.dtos;

import java.time.Instant;

/** A friend's calendar entry. Fields the viewer may not see are null. */
public record CalendarEntryResponse(
        Instant start,
        Instant end,
        String eventId,
        String title,
        String location,
        String description,
        boolean allDay
) {}
