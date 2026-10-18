package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Closeness;

import java.time.Instant;

/** A close friend not seen for a while; the last shared outing's fields are null when there never was one. */
public record ReunionResponse(
        String friendId,
        String name,
        Closeness closeness,
        Instant lastSharedAt,
        String lastSharedEventId,
        String lastSharedTitle,
        Integer monthsApart
) {}
