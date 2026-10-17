package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Visibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * What the organizer may change on an event: what it is, where, and who may see it. The date
 * moves through {@code PUT /events/{id}/schedule}, the cover through {@code /cover}.
 *
 * @param location    required, but on a surprise, whose place may still be open; checked by the domain, which knows the kind
 * @param coordinates null when the place was typed rather than picked on the map
 * @param visibility  null keeps the current one; ignored on a community event
 */
public record EventUpdateRequest(
        @NotBlank(message = "Title is required") String title,
        String location,
        @Valid CoordinatesDto coordinates,
        String description,
        Visibility visibility
) {}
