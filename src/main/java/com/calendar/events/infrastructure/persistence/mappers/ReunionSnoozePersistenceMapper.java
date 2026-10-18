package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.infrastructure.persistence.entities.ReunionSnoozeEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring")
public interface ReunionSnoozePersistenceMapper {

    String ID_SEPARATOR = ":";

    // The pair is the id: snoozing again replaces the snooze.
    @Mapping(target = "id", expression = "java(userId + ID_SEPARATOR + friendId)")
    ReunionSnoozeEntity toEntity(String userId, String friendId, Instant until);
}
