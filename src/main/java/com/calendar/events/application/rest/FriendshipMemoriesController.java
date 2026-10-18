package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.FriendshipMemoriesResponse;
import com.calendar.events.application.mappers.FriendshipRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.FriendshipMemories;
import com.calendar.events.domain.services.FriendshipMemoriesService;
import com.calendar.events.domain.services.PictureService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/** What the caller and one of their friends shared, for a friendship anniversary. */
@RestController
@RequestMapping("events/me/friendships")
public class FriendshipMemoriesController {

    private final FriendshipMemoriesService memoriesService;
    private final PictureService pictureService;
    private final FriendshipRestMapper mapper;

    public FriendshipMemoriesController(FriendshipMemoriesService memoriesService, PictureService pictureService,
                                        FriendshipRestMapper mapper) {
        this.memoriesService = memoriesService;
        this.pictureService = pictureService;
        this.mapper = mapper;
    }

    /** 404 EVT-BUS-036 unless the two are friends; the covers are signed in one round. */
    @GetMapping("/{friendId}/memories")
    public Mono<FriendshipMemoriesResponse> getMemories(@PathVariable String friendId, @AuthenticationPrincipal Jwt jwt) {
        return memoriesService.memoriesOf(jwt.getClaimAsString("businessId"), friendId)
                .flatMap(memories -> pictureService.signCovers(eventsOf(memories))
                        .map(urls -> mapper.toResponse(memories, urls)));
    }

    private static List<Event> eventsOf(FriendshipMemories memories) {
        List<Event> events = new ArrayList<>(memories.highlights());
        if (memories.first() != null) {
            events.add(memories.first());
        }
        if (memories.last() != null) {
            events.add(memories.last());
        }
        return events;
    }
}
