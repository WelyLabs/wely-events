package com.calendar.events.domain.models;

import java.time.Instant;

/** A proposed time for the event. The id is null until {@link Poll#open} gives it one. */
public record PollSlot(String id, Instant start, Instant end) {
}
