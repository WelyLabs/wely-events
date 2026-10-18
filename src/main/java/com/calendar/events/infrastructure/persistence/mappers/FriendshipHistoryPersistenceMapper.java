package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.infrastructure.persistence.entities.FriendshipHistoryEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FriendshipHistoryPersistenceMapper {

    /** The id a pair's version is unique under. */
    String ID_SEPARATOR = ":";

    @Mapping(target = "id", expression = "java(entry.pairId() + ID_SEPARATOR + entry.version())")
    @Mapping(target = "a", source = "userA")
    @Mapping(target = "b", source = "userB")
    FriendshipHistoryEntity toEntity(FriendshipHistoryEntry entry);

    @Mapping(target = "userA", source = "a")
    @Mapping(target = "userB", source = "b")
    FriendshipHistoryEntry toDomain(FriendshipHistoryEntity entity);
}
