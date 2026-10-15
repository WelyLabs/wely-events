package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventDeletionTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Test
    void of_shouldTakeTheVersionAfterTheLastGroupSent() {
        Event event = Event.builder().id("e1").title("Quiz").groupVersion(3L).build();

        assertThat(EventDeletion.of(event, NOW)).isEqualTo(new EventDeletion("e1", "Quiz", 4L, NOW));
    }

    @Test
    void of_shouldStartAtOne_forAnEventStoredBeforeGroupVersions() {
        Event event = Event.builder().id("e1").title("Quiz").build();

        assertThat(EventDeletion.of(event, NOW).version()).isEqualTo(1L);
    }
}
