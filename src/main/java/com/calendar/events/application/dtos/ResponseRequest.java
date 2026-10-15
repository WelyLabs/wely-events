package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.PollAnswer;
import jakarta.validation.constraints.NotNull;

/** A member's answer to a community event: YES, MAYBE or NO, the choices a date poll offers. */
public record ResponseRequest(@NotNull PollAnswer answer) {
}
