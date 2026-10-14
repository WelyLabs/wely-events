package com.calendar.events.application.dtos;

import jakarta.validation.constraints.NotBlank;

/** The slot the organizer settles on. */
public record FixDateRequest(@NotBlank String slotId) {
}
