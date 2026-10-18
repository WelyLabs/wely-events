package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.FriendshipMemoriesResponse;
import com.calendar.events.application.dtos.FriendshipMemoriesResponse.MemoryResponse;
import com.calendar.events.application.dtos.ReunionResponse;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventCover;
import com.calendar.events.domain.models.FriendshipMemories;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.models.SignedUrls;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring", imports = EventCover.class)
public interface FriendshipRestMapper {

    ReunionResponse toResponse(ReunionCandidate candidate);

    FriendshipMemoriesResponse toResponse(FriendshipMemories memories, @Context SignedUrls urls);

    // A stored cover is shown through its signed URL, as everywhere.
    @Mapping(target = "eventId", source = "id")
    @Mapping(target = "imageUrl", expression = "java(EventCover.of(event).shownWith(urls))")
    MemoryResponse toMemory(Event event, @Context SignedUrls urls);
}
