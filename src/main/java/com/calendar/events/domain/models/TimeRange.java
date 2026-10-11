package com.calendar.events.domain.models;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** A half-open interval of time, [start, end). */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
