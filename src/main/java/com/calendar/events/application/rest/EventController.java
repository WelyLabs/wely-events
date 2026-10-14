package com.calendar.events.application.rest;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

@RestController
@RequestMapping("events")
public class EventController {

    private final EventService eventService;
    private final PictureService pictureService;
    private final EventRestMapper mapper;

    public EventController(EventService eventService, PictureService pictureService, EventRestMapper mapper) {
        this.eventService = eventService;
        this.pictureService = pictureService;
        this.mapper = mapper;
    }

    @GetMapping("/me/subscribed")
    public Flux<EventResponse> getMySubscribedEvents(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(eventService.getSubscribedEvents(userId));
    }

    @GetMapping("/me/feed")
    public Flux<EventResponse> getMyFeedEvents(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(eventService.getFeedEvents(userId));
    }

    /** What the Explore map shows: upcoming events the caller may see, inside the area on screen. */
    @GetMapping("/map")
    public Flux<EventResponse> getMapEvents(@RequestParam double south, @RequestParam double west,
                                            @RequestParam double north, @RequestParam double east,
                                            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(Mono.fromCallable(() -> new GeoBox(south, west, north, east))
                .flatMapMany(box -> eventService.getMapEvents(userId, box)));
    }

    @GetMapping("/{id}")
    public Mono<EventResponse> getEventById(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(eventService.getEventById(id, userId));
    }

    /** Who takes part: everyone for those involved, how many and which friends for the others. */
    @GetMapping("/{id}/participants")
    public Mono<ParticipantsResponse> getParticipants(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.getParticipants(id, userId).map(mapper::toResponse);
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

        return respond(eventService.createEvent(event, mapper.toCandidateSlots(request.getCandidateSlots())));
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
        return respond(eventService.toggleSubscription(id, userId));
    }

    /** Multipart {@code file}, organizer only. 413 EVT-BUS-012 above 10 MB, 400 EVT-BUS-011 for anything but an image. */
    @PostMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<EventResponse> setCover(@PathVariable String id, @RequestBody Mono<MultiValueMap<String, Part>> form,
                                        @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(Uploads.fileOf(form, MediaProfile.COVER)
                .flatMap(content -> eventService.setCover(id, userId, content)));
    }

    @DeleteMapping("/{id}/cover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeCover(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.removeCover(id, userId);
    }

    /** One signing round for the whole list, so a feed costs one call to wely-media, not one per event. */
    private Flux<EventResponse> respond(Flux<Event> events) {
        return events.collectList()
                .flatMapMany(list -> pictureService.signCovers(list)
                        .flatMapIterable(urls -> list.stream().map(event -> mapper.toResponse(event, urls)).toList()));
    }

    private Mono<EventResponse> respond(Mono<Event> event) {
        return event.flatMap(found -> pictureService.signCovers(List.of(found))
                .map(urls -> mapper.toResponse(found, urls)));
    }
}
