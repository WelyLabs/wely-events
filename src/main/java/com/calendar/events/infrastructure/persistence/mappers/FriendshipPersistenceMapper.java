package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Friendship;
import com.calendar.events.infrastructure.persistence.entities.FriendshipEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FriendshipPersistenceMapper {

    @Mapping(target = "id", expression = "java(friendship.pairId())")
    @Mapping(target = "a", source = "userA")
    @Mapping(target = "b", source = "userB")
    FriendshipEntity toEntity(Friendship friendship);

    @Mapping(target = "userA", source = "a")
    @Mapping(target = "userB", source = "b")
    Friendship toDomain(FriendshipEntity entity);
}
