package com.calendar.events.application.dtos;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

/** A period to search, the friends to include, and how long the slot must last. */
public record FreeSlotSearchRequest(
        @NotEmpty @Size(max = 20) Set<String> friendIds,
        @NotNull Instant start,
        @NotNull Instant end,
        @NotNull @Min(15) @Max(89280) Integer durationMinutes
) {}
