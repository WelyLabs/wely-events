package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.infrastructure.persistence.entities.EventDeletionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EventDeletionPersistenceMapper {

    @Mapping(target = "id", source = "eventId")
    EventDeletionEntity toEntity(EventDeletion deletion);

    @Mapping(target = "eventId", source = "id")
    EventDeletion toDomain(EventDeletionEntity entity);
}
