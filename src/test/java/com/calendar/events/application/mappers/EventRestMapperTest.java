package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.CalendarEntryResponse;
import com.calendar.events.application.dtos.CoordinatesDto;
import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.Visibility;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class EventRestMapperTest {

    private final EventRestMapper mapper = Mappers.getMapper(EventRestMapper.class);

    @Test
    void toDomain_shouldMapRequestToDomain() {
        EventRequest request = new EventRequest();
        request.setTitle("Test Event");
        request.setStartDate(Instant.now());
        request.setEndDate(Instant.now().plusSeconds(3600));
        request.setLocation("Paris");
        request.setDescription("Desc");
        request.setSubscribeByDefault(true);

        Event domain = mapper.toDomain(request);

        assertNotNull(domain);
        assertEquals(request.getTitle(), domain.getTitle());
        assertEquals(request.getStartDate(), domain.getStartDate());
        assertEquals(request.getEndDate(), domain.getEndDate());
        assertEquals(request.getLocation(), domain.getLocation());
        assertEquals(request.getDescription(), domain.getDescription());
        assertNull(domain.getId()); // Ignored in mapping
        assertNull(domain.getOrganizerId()); // Ignored in mapping
    }

    @Test
    void toResponse_shouldMapDomainToResponse() {
        Event domain = Event.builder()
                .id("1")
                .title("Test Event")
                .organizerId("org1")
                .startDate(Instant.now())
                .endDate(Instant.now().plusSeconds(3600))
                .location("Paris")
                .description("Desc")
                .participantIds(Collections.singletonList("user1"))
                .build();

        EventResponse response = mapper.toResponse(domain);

        assertNotNull(response);
        assertEquals(domain.getId(), response.getId());
        assertEquals(domain.getTitle(), response.getTitle());
        assertEquals(domain.getOrganizerId(), response.getOrganizerId());
        assertEquals(domain.getStartDate(), response.getStartDate());
        assertEquals(domain.getEndDate(), response.getEndDate());
        assertEquals(domain.getLocation(), response.getLocation());
        assertEquals(domain.getDescription(), response.getDescription());
    }

    @Test
    void toDomain_shouldCarryTheChosenVisibility() {
        EventRequest request = EventRequest.builder().title("t").visibility(Visibility.PUBLIC).build();

        assertEquals(Visibility.PUBLIC, mapper.toDomain(request).getVisibility());
    }

    @Test
    void toResponse_shouldReportAMissingVisibilityAsFriends() {
        EventResponse response = mapper.toResponse(Event.builder().id("1").build());

        assertEquals(Visibility.FRIENDS, response.getVisibility());
    }

    @Test
    void toResponse_shouldMapACalendarEntryAsIs() {
        Instant start = Instant.parse("2026-10-17T18:00:00Z");
        CalendarEntry entry = new CalendarEntry(start, start.plusSeconds(3600), null, "Dinner", null, null);

        assertEquals(new CalendarEntryResponse(start, start.plusSeconds(3600), null, "Dinner", null, null),
                mapper.toResponse(entry));
    }

    @Test
    void toDomain_shouldCarryThePointPickedOnTheMap() {
        EventRequest request = EventRequest.builder().title("t").coordinates(new CoordinatesDto(45.764, 4.8357)).build();

        assertEquals(new GeoPoint(45.764, 4.8357), mapper.toDomain(request).getCoordinates());
    }

    @Test
    void toResponse_shouldGiveBackTheCoordinates() {
        Event domain = Event.builder().coordinates(new GeoPoint(45.764, 4.8357)).build();

        assertEquals(new CoordinatesDto(45.764, 4.8357), mapper.toResponse(domain).getCoordinates());
    }

    @Test
    void toDomain_shouldLeaveATypedLocationWithoutCoordinates() {
        assertNull(mapper.toDomain(EventRequest.builder().title("t").location("Lyon").build()).getCoordinates());
        assertNull(mapper.toResponse(Event.builder().location("Lyon").build()).getCoordinates());
    }
}
