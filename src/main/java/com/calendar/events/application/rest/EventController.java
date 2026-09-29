package com.calendar.events.application.rest;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("events")
public class EventController {

    private final EventService eventService;
    private final EventRestMapper mapper;

    public EventController(EventService eventService, EventRestMapper mapper) {
        this.eventService = eventService;
        this.mapper = mapper;
    }

    @GetMapping("/me/subscribed")
    public Flux<EventResponse> getMySubscribedEvents(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.getSubscribedEvents(userId).map(mapper::toResponse);
    }

    @GetMapping("/me/feed")
    public Flux<EventResponse> getMyFeedEvents(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.getFeedEvents(userId).map(mapper::toResponse);
    }

    @GetMapping("/{id}")
    public Mono<EventResponse> getEventById(@PathVariable String id) {
        return eventService.getEventById(id).map(mapper::toResponse);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<EventResponse> createEvent(@Valid @RequestBody EventRequest request, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        Event event = mapper.toDomain(request);
        event.setOrganizerId(userId);

        if (request.isSubscribeByDefault()) {
            event.getParticipantIds().add(userId);
        }

        return eventService.createEvent(event).map(mapper::toResponse);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteEvent(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.deleteEvent(id, userId);
    }

    @PostMapping("/{id}/subscribe")
    public Mono<EventResponse> toggleSubscription(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.toggleSubscription(id, userId).map(mapper::toResponse);
    }
}
