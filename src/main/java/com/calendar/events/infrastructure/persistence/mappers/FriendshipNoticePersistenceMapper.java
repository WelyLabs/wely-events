package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.infrastructure.persistence.entities.FriendshipNoticeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring")
public interface FriendshipNoticePersistenceMapper {

    // The key is the id: a second insert of the same notice fails on it.
    @Mapping(target = "id", expression = "java(notice.key())")
    @Mapping(target = "published", constant = "false")
    FriendshipNoticeEntity toEntity(FriendshipNotice notice, Instant recordedAt);

    FriendshipNotice toDomain(FriendshipNoticeEntity entity);
}
