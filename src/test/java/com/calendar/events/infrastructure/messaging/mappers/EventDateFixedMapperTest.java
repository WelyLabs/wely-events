package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.infrastructure.messaging.models.EventDateFixedDTO;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventDateFixedMapperTest {

    private final EventDateFixedMapper mapper = Mappers.getMapper(EventDateFixedMapper.class);

    @Test
    void toDto_shouldAnnounceTheEventsFixedDates() {
        EventEntity event = EventEntity.builder().id("e1").title("Dinner").organizerId("org")
                .startDate(Instant.parse("2026-10-16T17:00:00Z")).endDate(Instant.parse("2026-10-16T19:00:00Z"))
                .build();

        assertThat(mapper.toDto(event)).isEqualTo(new EventDateFixedDTO("e1", "Dinner",
                Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"), "org", null, null));
    }

    @Test
    void toDto_shouldCarryThePreviousDates_ofAMove() {
        EventEntity event = EventEntity.builder().id("e1").title("Bowling").organizerId("org")
                .startDate(Instant.parse("2026-10-16T18:00:00Z")).endDate(Instant.parse("2026-10-16T20:00:00Z"))
                .rescheduledFromStart(Instant.parse("2026-10-16T17:00:00Z"))
                .rescheduledFromEnd(Instant.parse("2026-10-16T19:00:00Z"))
                .build();

        assertThat(mapper.toDto(event)).isEqualTo(new EventDateFixedDTO("e1", "Bowling",
                Instant.parse("2026-10-16T18:00:00Z"), Instant.parse("2026-10-16T20:00:00Z"), "org",
                Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z")));
    }

    @Test
    void toDto_shouldBeNull_forNoEvent() {
        assertThat(mapper.toDto(null)).isNull();
    }
}
