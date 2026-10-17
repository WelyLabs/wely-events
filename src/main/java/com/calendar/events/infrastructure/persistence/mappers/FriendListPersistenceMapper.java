package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendList;
import com.calendar.events.infrastructure.persistence.entities.FriendListEntity;
import com.calendar.events.infrastructure.persistence.entities.FriendListEntity.FriendEntry;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface FriendListPersistenceMapper {

    @Mapping(target = "id", source = "userId")
    FriendListEntity toEntity(FriendList list);

    @Mapping(target = "userId", source = "id")
    FriendList toDomain(FriendListEntity entity);

    // Stored as an array, not keyed by id, so "whose lists name this user" is one indexed match.
    default List<FriendEntry> toEntries(Map<String, Closeness> friends) {
        return friends.entrySet().stream()
                .map(friend -> new FriendEntry(friend.getKey(), friend.getValue().name()))
                .toList();
    }

    default Map<String, Closeness> toFriends(List<FriendEntry> entries) {
        Map<String, Closeness> friends = new HashMap<>();
        if (entries != null) {
            entries.forEach(entry -> friends.put(entry.getUserId(), Closeness.valueOf(entry.getCloseness())));
        }
        return friends;
    }
}
