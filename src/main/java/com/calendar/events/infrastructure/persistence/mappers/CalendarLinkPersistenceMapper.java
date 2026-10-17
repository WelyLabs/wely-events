package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.infrastructure.persistence.entities.CalendarLinkEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface CalendarLinkPersistenceMapper {

    @Mapping(target = "id", source = "token")
    CalendarLinkEntity toEntity(CalendarLink link);

    @Mapping(target = "token", source = "id")
    CalendarLink toDomain(CalendarLinkEntity entity);
}
