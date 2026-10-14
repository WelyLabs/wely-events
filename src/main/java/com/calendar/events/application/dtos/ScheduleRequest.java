package com.calendar.events.application.dtos;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/** Where the organizer moves an event to. */
public record ScheduleRequest(
        @NotNull Instant start,
        @NotNull Instant end
) {}
