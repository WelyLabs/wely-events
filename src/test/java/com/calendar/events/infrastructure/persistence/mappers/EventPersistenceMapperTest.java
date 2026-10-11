package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class EventPersistenceMapperTest {

    private final EventPersistenceMapper mapper = Mappers.getMapper(EventPersistenceMapper.class);

    @Test
    void toDomain_shouldMapEntityToDomain() {
        EventEntity entity = new EventEntity();
        entity.setId("1");
        entity.setTitle("Test Event");
        entity.setOrganizerId("org1");
        entity.setStartDate(Instant.now());
        entity.setEndDate(Instant.now().plusSeconds(3600));
        entity.setParticipantIds(Collections.singletonList("user1"));

        Event domain = mapper.toDomain(entity);

        assertNotNull(domain);
        assertEquals(entity.getId(), domain.getId());
        assertEquals(entity.getTitle(), domain.getTitle());
        assertEquals(entity.getOrganizerId(), domain.getOrganizerId());
        assertEquals(entity.getStartDate(), domain.getStartDate());
        assertEquals(entity.getEndDate(), domain.getEndDate());
        assertEquals(entity.getParticipantIds(), domain.getParticipantIds());
    }

    @Test
    void toEntity_shouldMapDomainToEntity() {
        Event domain = Event.builder()
                .id("1")
                .title("Test Event")
                .organizerId("org1")
                .startDate(Instant.now())
                .endDate(Instant.now().plusSeconds(3600))
                .participantIds(Collections.singletonList("user1"))
                .build();

        EventEntity entity = mapper.toEntity(domain);

        assertNotNull(entity);
        assertEquals(domain.getId(), entity.getId());
        assertEquals(domain.getTitle(), entity.getTitle());
        assertEquals(domain.getOrganizerId(), entity.getOrganizerId());
        assertEquals(domain.getStartDate(), entity.getStartDate());
        assertEquals(domain.getEndDate(), entity.getEndDate());
        assertEquals(domain.getParticipantIds(), entity.getParticipantIds());
    }

    @Test
    void visibility_shouldRoundTripAsItsName() {
        EventEntity entity = mapper.toEntity(Event.builder().visibility(Visibility.PRIVATE).build());

        assertEquals("PRIVATE", entity.getVisibility());
        assertEquals(Visibility.PRIVATE, mapper.toDomain(entity).getVisibility());
    }

    @Test
    void toDomain_shouldLeaveAMissingVisibilityNull() {
        assertNull(mapper.toDomain(new EventEntity()).getVisibility());
    }
}
