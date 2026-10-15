package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.infrastructure.persistence.entities.EventDeletionEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventDeletionPersistenceMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final EventDeletionPersistenceMapper mapper = Mappers.getMapper(EventDeletionPersistenceMapper.class);

    @Test
    void toEntity_shouldKeyTheTombstoneByEvent() {
        EventDeletionEntity entity = mapper.toEntity(new EventDeletion("e1", "Quiz", 4L, NOW));

        assertThat(entity).usingRecursiveComparison().isEqualTo(new EventDeletionEntity("e1", "Quiz", 4L, NOW));
    }

    @Test
    void toDomain_shouldGiveBackTheEventId() {
        assertThat(mapper.toDomain(new EventDeletionEntity("e1", "Quiz", 4L, NOW)))
                .isEqualTo(new EventDeletion("e1", "Quiz", 4L, NOW));
    }
}
