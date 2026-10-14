package com.calendar.events.application.dtos;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** A time to check against the caller's agenda, and the event it belongs to if it is saved already. */
public record ConflictCheckRequest(
        @NotNull Instant start,
        @NotNull Instant end,
        String excludedEventId
) {}
