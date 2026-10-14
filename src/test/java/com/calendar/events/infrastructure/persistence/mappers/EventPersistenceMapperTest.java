package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    @Test
    void toEntity_shouldStoreTheCoordinatesAsGeoJsonLongitudeFirst() {
        Event domain = Event.builder().coordinates(new GeoPoint(45.764, 4.8357)).build();

        assertEquals(new GeoJsonPoint(4.8357, 45.764), mapper.toEntity(domain).getCoordinates());
    }

    @Test
    void toDomain_shouldReadTheGeoJsonBackAsLatitudeAndLongitude() {
        EventEntity entity = new EventEntity();
        entity.setCoordinates(new GeoJsonPoint(4.8357, 45.764));

        assertEquals(new GeoPoint(45.764, 4.8357), mapper.toDomain(entity).getCoordinates());
    }

    @Test
    void toGeoJson_shouldKeepAMissingPointNull() {
        assertNull(mapper.toGeoJson(null));
        assertNull(mapper.toGeoPoint(null));
    }

    @Test
    void toEntity_shouldStoreThePollWithItsBallots_andReadItBack() {
        PollSlot slot = new PollSlot("thu", Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"));
        Poll poll = new Poll(PollStatus.OPEN, List.of(slot), Map.of("lea", Map.of("thu", PollAnswer.MAYBE)), null);
        Event domain = Event.builder().id("1").poll(poll).build();

        EventEntity entity = mapper.toEntity(domain);

        assertEquals("OPEN", entity.getPoll().getStatus());
        assertEquals("thu", entity.getPoll().getSlots().getFirst().getId());
        assertEquals(Map.of("lea", Map.of("thu", "MAYBE")), entity.getPoll().getBallots());
        assertNull(entity.getDateFixSynced());
        assertEquals(poll, mapper.toDomain(entity).getPoll());
    }

    @Test
    void toDomain_shouldLeaveThePollNull_forAnEventWithoutOne() {
        assertNull(mapper.toDomain(new EventEntity()).getPoll());
        assertNull(mapper.toEntity(Event.builder().build()).getPoll());
    }
}
