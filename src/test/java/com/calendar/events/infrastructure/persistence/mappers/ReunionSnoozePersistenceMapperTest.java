package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.infrastructure.persistence.entities.ReunionSnoozeEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReunionSnoozePersistenceMapperTest {

    private final ReunionSnoozePersistenceMapper mapper = Mappers.getMapper(ReunionSnoozePersistenceMapper.class);

    @Test
    void toEntity_shouldKeyTheSnoozeByPairAndDirection() {
        Instant until = Instant.parse("2026-11-17T08:00:00Z");

        ReunionSnoozeEntity entity = mapper.toEntity("me", "hugo", until);

        assertThat(entity.getId()).isEqualTo("me:hugo");
        assertThat(entity.getUserId()).isEqualTo("me");
        assertThat(entity.getFriendId()).isEqualTo("hugo");
        assertThat(entity.getUntil()).isEqualTo(until);
    }
}
