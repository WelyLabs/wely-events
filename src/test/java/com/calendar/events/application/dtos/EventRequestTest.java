package com.calendar.events.application.dtos;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventRequestTest {

    @Test
    void isDateGiven_shouldAcceptAStartDate_orCandidateSlots_butNotNeither() {
        Instant start = Instant.parse("2026-10-16T17:00:00Z");

        assertThat(EventRequest.builder().startDate(start).build().isDateGiven()).isTrue();
        assertThat(EventRequest.builder().candidateSlots(List.of(new SlotRequest(start, start.plusSeconds(3600))))
                .build().isDateGiven()).isTrue();
        assertThat(EventRequest.builder().build().isDateGiven()).isFalse();
    }

    @Test
    void isLocationGiven_shouldRequireALocation_butOnASurprise() {
        assertThat(EventRequest.builder().location("Lyon").build().isLocationGiven()).isTrue();
        assertThat(EventRequest.builder().location(" ").build().isLocationGiven()).isFalse();
        assertThat(EventRequest.builder().build().isLocationGiven()).isFalse();
        assertThat(EventRequest.builder().kind(com.calendar.events.domain.models.EventKind.SURPRISE).build()
                .isLocationGiven()).isTrue();
    }
}
