package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendList;
import com.calendar.events.infrastructure.persistence.entities.FriendListEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FriendListPersistenceMapperTest {

    private final FriendListPersistenceMapper mapper = Mappers.getMapper(FriendListPersistenceMapper.class);
    private static final Instant NOW = Instant.parse("2027-02-19T07:00:00Z");

    @Test
    void toEntity_shouldStoreTheFriendsAsAnArray_andComeBackTheSame() {
        FriendList list = new FriendList("lea", Map.of("bob", Closeness.CLOSE, "zoe", Closeness.INTIMATE), NOW);

        FriendListEntity entity = mapper.toEntity(list);

        assertThat(entity.getId()).isEqualTo("lea");
        assertThat(entity.getFriends()).extracting(FriendListEntity.FriendEntry::getUserId).containsExactlyInAnyOrder("bob", "zoe");
        assertThat(mapper.toDomain(entity)).isEqualTo(list);
    }

    @Test
    void toFriends_shouldReadAMissingArrayAsNoFriends() {
        assertThat(mapper.toFriends(null)).isEmpty();
    }

    @Test
    void toEntries_shouldNameEachClosenessByItsName() {
        assertThat(mapper.toEntries(Map.of("bob", Closeness.FRIEND)))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getCloseness()).isEqualTo("FRIEND"));
    }
}
