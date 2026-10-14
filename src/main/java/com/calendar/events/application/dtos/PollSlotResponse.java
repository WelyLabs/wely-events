package com.calendar.events.application.dtos;

import java.time.Instant;
import java.util.List;

/** A proposed slot and who answered what, by businessId. */
public record PollSlotResponse(String id, Instant start, Instant end,
                               List<String> yes, List<String> maybe, List<String> no) {
}
