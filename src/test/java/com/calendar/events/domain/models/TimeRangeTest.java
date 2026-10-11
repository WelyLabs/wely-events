package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeRangeTest {

    private static final Instant NOON = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void duration_shouldBeTheLengthOfTheRange() {
        assertThat(new TimeRange(NOON, NOON.plusSeconds(5400)).duration()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void constructor_shouldRejectAnEmptyOrReversedRange() {
        assertThatThrownBy(() -> new TimeRange(NOON, NOON)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeRange(NOON, NOON.minusSeconds(1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_shouldRejectAMissingBound() {
        assertThatThrownBy(() -> new TimeRange(null, NOON)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TimeRange(NOON, null)).isInstanceOf(NullPointerException.class);
    }
}
