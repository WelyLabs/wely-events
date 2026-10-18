package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendshipChangeType;
import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.infrastructure.persistence.entities.FriendshipHistoryEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipHistoryPersistenceMapperTest {

    private static final Instant AT = Instant.parse("2026-10-18T08:00:00Z");
    private final FriendshipHistoryPersistenceMapper mapper = Mappers.getMapper(FriendshipHistoryPersistenceMapper.class);

    @Test
    void toEntity_shouldKeyTheEntryByPairAndVersion() {
        FriendshipHistoryEntity entity = mapper.toEntity(new FriendshipHistoryEntry("alice:bob", "alice", "bob",
                FriendshipChangeType.CLOSENESS_CHANGED, Closeness.INTIMATE, Closeness.CLOSE, AT, 7));

        assertThat(entity.getId()).isEqualTo("alice:bob:7");
        assertThat(entity.getA()).isEqualTo("alice");
        assertThat(entity.getB()).isEqualTo("bob");
        assertThat(entity.getType()).isEqualTo("CLOSENESS_CHANGED");
        assertThat(entity.getCloseness()).isEqualTo("INTIMATE");
        assertThat(entity.getPreviousCloseness()).isEqualTo("CLOSE");
        assertThat(entity.getAt()).isEqualTo(AT);
        assertThat(entity.getVersion()).isEqualTo(7);
    }

    @Test
    void toDomain_shouldReadTheEntryBack_noClosenessIncluded() {
        FriendshipHistoryEntry entry = new FriendshipHistoryEntry("alice:bob", "alice", "bob", FriendshipChangeType.REMOVED,
                null, Closeness.FRIEND, AT, 8);

        assertThat(mapper.toDomain(mapper.toEntity(entry))).isEqualTo(entry);
    }
}
