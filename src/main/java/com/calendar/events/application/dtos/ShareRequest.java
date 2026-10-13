package com.calendar.events.application.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/** A weight with SHARES, cents with EXACT, ignored with EQUAL. */
public record ShareRequest(@NotBlank String userId, @PositiveOrZero long value) {}
