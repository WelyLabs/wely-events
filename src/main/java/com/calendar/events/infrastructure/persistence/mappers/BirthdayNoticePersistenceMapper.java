package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.infrastructure.persistence.entities.BirthdayNoticeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring")
public interface BirthdayNoticePersistenceMapper {

    // The key is the id: a second insert of the same notice fails on it.
    @Mapping(target = "id", expression = "java(notice.key())")
    @Mapping(target = "published", constant = "false")
    BirthdayNoticeEntity toEntity(BirthdayNotice notice, Instant recordedAt);

    BirthdayNotice toDomain(BirthdayNoticeEntity entity);
}
