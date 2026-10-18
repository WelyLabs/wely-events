package com.calendar.events.application.dtos;

import java.time.Instant;

/** An intimate friend's calendar entry, and whose. Fields the caller may not see are null. */
public record CircleEntryResponse(
        String userId,
        Instant start,
        Instant end,
        boolean allDay,
        String eventId,
        String title,
        String location,
        String description
) {}
