package com.calendar.events.application.dtos;

import jakarta.validation.constraints.NotBlank;

/** The friend to invite, by business id. */
public record InvitationRequest(@NotBlank(message = "userId is required") String userId) {}
