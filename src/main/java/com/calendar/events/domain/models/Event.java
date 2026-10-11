package com.calendar.events.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    /** How long an event without an end is taken to last, as the calendar displays it. */
    public static final Duration DEFAULT_DURATION = Duration.ofHours(1);

    private String id;
    private String title;
    private String organizerId;
    private Instant startDate;
    private Instant endDate;
    private String location;
    private String image;
    private String description;
    @Builder.Default
    private List<String> participantIds = new ArrayList<>();

    /** When the event stops keeping its people busy. */
    public Instant effectiveEnd() {
        return endDate != null ? endDate : startDate.plus(DEFAULT_DURATION);
    }
}
