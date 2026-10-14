package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.infrastructure.messaging.models.EventDateFixedDTO;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface EventDateFixedMapper {

    // The fix moved the event's dates onto the chosen slot, in the same update that flagged it.
    @Mapping(target = "eventId", source = "id")
    @Mapping(target = "start", source = "startDate")
    @Mapping(target = "end", source = "endDate")
    EventDateFixedDTO toDto(EventEntity entity);
}
