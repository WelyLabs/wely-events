package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.TrackedPair;
import com.calendar.events.infrastructure.persistence.entities.FriendshipEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipPersistenceMapperTest {

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");

    private final FriendshipPersistenceMapper mapper = Mappers.getMapper(FriendshipPersistenceMapper.class);

    @Test
    void toEntity_shouldKeyThePairByItsSortedIds() {
        FriendshipEntity entity = mapper.toEntity(new Friendship("bob", "alice", true, Closeness.INTIMATE, SINCE, 9));

        assertThat(entity.getId()).isEqualTo("alice:bob");
        assertThat(entity.getA()).isEqualTo("alice");
        assertThat(entity.getB()).isEqualTo("bob");
        assertThat(entity.isFriends()).isTrue();
        assertThat(entity.getCloseness()).isEqualTo("INTIMATE");
        assertThat(entity.getSince()).isEqualTo(SINCE);
        assertThat(entity.getVersion()).isEqualTo(9);
        assertThat(entity.getStreamedVersion()).isEqualTo(9L);
    }

    @Test
    void toEntity_shouldStoreATombstoneWithoutCloseness() {
        FriendshipEntity entity = mapper.toEntity(new Friendship("alice", "bob", false, null, null, 10));

        assertThat(entity.isFriends()).isFalse();
        assertThat(entity.getCloseness()).isNull();
        assertThat(entity.getSince()).isNull();
    }

    @Test
    void toDomain_shouldReadThePairBack() {
        Friendship friendship = new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 4);

        assertThat(mapper.toDomain(mapper.toEntity(friendship))).isEqualTo(friendship);
        assertThat(mapper.toDomain(null)).isNull();
    }

    @Test
    void toTracked_shouldReadThePairAndItsLastStreamedVersion() {
        Friendship friendship = new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 4);
        FriendshipEntity entity = mapper.toEntity(friendship);
        entity.setStreamedVersion(3L);

        assertThat(mapper.toTracked(entity)).isEqualTo(new TrackedPair(friendship, 3L));
        assertThat(mapper.toTracked(null)).isNull();
    }
}
