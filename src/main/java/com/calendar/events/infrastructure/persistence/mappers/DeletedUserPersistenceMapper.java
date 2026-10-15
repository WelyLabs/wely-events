package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.DeletedUserMarker;
import com.calendar.events.infrastructure.persistence.entities.DeletedUserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface DeletedUserPersistenceMapper {

    @Mapping(target = "id", source = "userId")
    DeletedUserEntity toEntity(DeletedUserMarker marker);

    @Mapping(target = "userId", source = "id")
    DeletedUserMarker toDomain(DeletedUserEntity entity);
}
