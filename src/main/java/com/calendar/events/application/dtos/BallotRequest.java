package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.PollAnswer;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/** The caller's whole ballot, slot id to answer; it replaces the previous one, and empty withdraws it. */
public record BallotRequest(@NotNull Map<String, @NotNull PollAnswer> answers) {
}
