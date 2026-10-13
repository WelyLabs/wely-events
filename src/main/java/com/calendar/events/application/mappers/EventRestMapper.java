package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.CalendarEntryResponse;
import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventCover;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.ParticipantsResponse;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", imports = EventCover.class)
public interface EventRestMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "participantIds", ignore = true)
    @Mapping(target = "organizerId", ignore = true)
    @Mapping(target = "image", ignore = true)
    Event toDomain(EventRequest request);

    // A stored cover is shown through its signed URL; urls comes from one signing round per response.
    @Mapping(target = "visibility", expression = "java(domain.effectiveVisibility())")
    @Mapping(target = "image", expression = "java(EventCover.of(domain).shownWith(urls))")
    EventResponse toResponse(Event domain, @Context SignedUrls urls);

    CalendarEntryResponse toResponse(CalendarEntry entry);

    ParticipantsResponse toResponse(Participants participants);
}
