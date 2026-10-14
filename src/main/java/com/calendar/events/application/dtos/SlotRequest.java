package com.calendar.events.application.dtos;

import java.time.Instant;

/** A proposed slot; checked by the domain, so every flaw answers EVT-BUS-014. */
public record SlotRequest(Instant start, Instant end) {
}
