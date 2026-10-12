package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Mapper(componentModel = "spring")
public interface EventGroupMapper {

    @Mapping(target = "eventId", source = "id")
    @Mapping(target = "memberIds", source = ".", qualifiedByName = "members")
    @Mapping(target = "version", source = "groupVersion", defaultValue = "0L")
    EventGroupChangedDTO toDto(EventEntity entity);

    /** The organizer first, then participants in joining order, each once. */
    @Named("members")
    default List<String> members(EventEntity entity) {
        Set<String> members = new LinkedHashSet<>();
        if (entity.getOrganizerId() != null) {
            members.add(entity.getOrganizerId());
        }
        if (entity.getParticipantIds() != null) {
            members.addAll(entity.getParticipantIds());
        }
        return List.copyOf(members);
    }
}
