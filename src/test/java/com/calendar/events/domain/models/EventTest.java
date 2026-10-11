package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventTest {

    private static final Instant START = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void effectiveEnd_shouldBeTheEndWhenThereIsOne() {
        Event event = Event.builder().startDate(START).endDate(START.plusSeconds(7200)).build();

        assertThat(event.effectiveEnd()).isEqualTo(START.plusSeconds(7200));
    }

    @Test
    void effectiveEnd_shouldDefaultToOneHour_whenTheEventHasNoEnd() {
        Event event = Event.builder().startDate(START).build();

        assertThat(event.effectiveEnd()).isEqualTo(START.plus(Event.DEFAULT_DURATION));
    }
}
