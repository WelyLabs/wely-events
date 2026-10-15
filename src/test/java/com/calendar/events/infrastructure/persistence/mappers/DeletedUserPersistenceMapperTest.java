package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.DeletedUserMarker;
import com.calendar.events.infrastructure.persistence.entities.DeletedUserEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeletedUserPersistenceMapperTest {

    private static final Instant DELETED = Instant.parse("2026-10-15T14:03:27.123Z");
    private static final Instant ERASED = Instant.parse("2026-10-15T14:03:29Z");

    private final DeletedUserPersistenceMapper mapper = Mappers.getMapper(DeletedUserPersistenceMapper.class);

    @Test
    void toEntity_shouldStoreTheUserIdAsTheDocumentId() {
        DeletedUserEntity entity = mapper.toEntity(new DeletedUserMarker("u1", DELETED, null));

        assertThat(entity).usingRecursiveComparison().isEqualTo(new DeletedUserEntity("u1", DELETED, null));
    }

    @Test
    void toDomain_shouldReadTheDocumentIdAsTheUserId() {
        assertThat(mapper.toDomain(new DeletedUserEntity("u1", DELETED, ERASED)))
                .isEqualTo(new DeletedUserMarker("u1", DELETED, ERASED));
    }
}
