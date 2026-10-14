package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.PollStatus;

import java.util.List;

/** A date poll as clients see it: slots sorted by start, fixedSlotId null while open. */
public record PollResponse(PollStatus status, String fixedSlotId, List<PollSlotResponse> slots) {
}
