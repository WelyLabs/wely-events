package com.calendar.events.application.rest;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.domain.models.FeedRank;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.SurpriseService;
import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.EventUpdateRequest;
import com.calendar.events.application.dtos.LeaveRequest;
import com.calendar.events.application.dtos.LeaveResponse;
import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.dtos.ResponseRequest;
import com.calendar.events.application.mappers.EventRestMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

@RestController
@RequestMapping("events")
public class EventController {

    /** The organizer's name in a surprise's notices, as wely-chat names a call's caller. */
    static final String USERNAME_CLAIM = "preferred_username";

    private final EventService eventService;
    private final SurpriseService surpriseService;
    private final PictureService pictureService;
    private final FriendshipService friendshipService;
    private final EventRestMapper mapper;

    public EventController(EventService eventService, SurpriseService surpriseService, PictureService pictureService,
                           FriendshipService friendshipService, EventRestMapper mapper) {
        this.eventService = eventService;
        this.surpriseService = surpriseService;
        this.pictureService = pictureService;
        this.friendshipService = friendshipService;
        this.mapper = mapper;
    }

    /**
     * The caller's own events. With {@code from} and {@code to} the answer is narrowed to the events
     * overlapping that range, soonest first; without them it is the whole calendar, as before.
     */
    @GetMapping("/me/subscribed")
    public Flux<EventResponse> getMySubscribedEvents(@RequestParam(required = false) Instant from,
                                                     @RequestParam(required = false) Instant to,
                                                     @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        Flux<Event> events = from == null && to == null
                ? eventService.getSubscribedEvents(userId)
                : eventService.getSubscribedEvents(userId, from, to);
        return respond(events, userId);
    }

    /**
     * What the caller may discover. {@code rank=affinity} puts the events their closest friends go to
     * first, then the soonest; without it, the usual order. 400 EVT-BUS-035 for any other rank.
     */
    @GetMapping("/me/feed")
    public Flux<EventResponse> getMyFeedEvents(@RequestParam(required = false) String rank,
                                               @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return Mono.justOrEmpty(FeedRank.named(rank))
                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.FEED_RANK_INVALID)))
                .flatMapMany(feedRank -> friendshipService.viewerFriends(userId)
                        .flatMapMany(friends -> respond(eventService.getFeedEvents(userId, friends, feedRank), friends)));
    }

    /** What the Explore map shows: upcoming events the caller may see, inside the area on screen. */
    @GetMapping("/map")
    public Flux<EventResponse> getMapEvents(@RequestParam double south, @RequestParam double west,
                                            @RequestParam double north, @RequestParam double east,
                                            @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(Mono.fromCallable(() -> new GeoBox(south, west, north, east))
                .flatMapMany(box -> eventService.getMapEvents(userId, box)), userId);
    }

    /** A community's upcoming events, soonest first; 403 EVT-BUS-024 for anyone but its members. */
    @GetMapping("/community/{communityId}")
    public Flux<EventResponse> getCommunityEvents(@PathVariable String communityId, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(eventService.getCommunityEvents(communityId, userId), userId);
    }

    /**
     * A member's answer to a community event, which they may change at any time; 409 EVT-BUS-023 for
     * an event outside any community. YES takes part, MAYBE and NO do not.
     */
    @PutMapping("/{id}/response")
    public Mono<EventResponse> respondTo(@PathVariable String id, @Valid @RequestBody ResponseRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(eventService.respond(id, userId, request.answer()), userId);
    }

    @GetMapping("/{id}")
    public Mono<EventResponse> getEventById(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(eventService.getEventById(id, userId), userId);
    }

    /** Who takes part: everyone for those involved, how many and which friends for the others. */
    @GetMapping("/{id}/participants")
    public Mono<ParticipantsResponse> getParticipants(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.getParticipants(id, userId).map(mapper::toResponse);
    }

    /**
     * A standard event, or with {@code kind: SURPRISE} and {@code honoreeId} a surprise birthday, hidden
     * from the honoree: 403 EVT-BUS-028 for anyone but a close friend, 409 EVT-BUS-029 without a
     * birthday, 409 EVT-BUS-030 with the existing surprise's {@code eventId} when there is one already.
     * 400 EVT-BUS-038 for a date already past, surprise or not.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<EventResponse> createEvent(@Valid @RequestBody EventRequest request, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        Event event = mapper.toDomain(request);
        event.setOrganizerId(userId);
        var candidateSlots = mapper.toCandidateSlots(request.getCandidateSlots());
        return respond(request.getKind() == EventKind.SURPRISE
                ? surpriseService.createSurprise(event, candidateSlots, jwt.getClaimAsString(USERNAME_CLAIM))
                : eventService.createEvent(event, candidateSlots), userId);
    }

    /**
     * Organizer only: changes the title, place, description or visibility. The date moves through
     * {@code PUT /{id}/schedule}. 403 EVT-BUS-002 for someone else's event, 404 EVT-BUS-001 for one not visible.
     */
    @PutMapping("/{id}")
    public Mono<EventResponse> updateEvent(@PathVariable String id, @Valid @RequestBody EventUpdateRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(eventService.updateDetails(id, userId, mapper.toDetails(request)), userId);
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
        return respond(eventService.toggleSubscription(id, userId), userId);
    }

    /**
     * Leaves the event. The organizer names a successor when others take part (409 EVT-BUS-020
     * without one), and confirms with {@code deleteIfLast} when alone, which deletes the event
     * (409 EVT-BUS-022 without it). No body counts as neither.
     */
    @PostMapping("/{id}/leave")
    public Mono<LeaveResponse> leave(@PathVariable String id, @RequestBody(required = false) LeaveRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        LeaveRequest choice = request != null ? request : new LeaveRequest(null, false);
        return eventService.leave(id, userId, choice.newOrganizerId(), choice.confirmsDeletion())
                .flatMap(outcome -> Mono.zip(
                                pictureService.signCovers(outcome.event() != null ? List.of(outcome.event()) : List.of()),
                                friendshipService.viewerFriends(userId))
                        .map(known -> mapper.toResponse(outcome, known.getT1(), known.getT2())));
    }

    /** Multipart {@code file}, organizer only. 413 EVT-BUS-012 above 10 MB, 400 EVT-BUS-011 for anything but an image. */
    @PostMapping(value = "/{id}/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<EventResponse> setCover(@PathVariable String id, @RequestBody Mono<MultiValueMap<String, Part>> form,
                                        @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(Uploads.fileOf(form, MediaProfile.COVER)
                .flatMap(content -> eventService.setCover(id, userId, content)), userId);
    }

    @DeleteMapping("/{id}/cover")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeCover(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return eventService.removeCover(id, userId);
    }

    /**
     * One signing round for the whole list, so a feed costs one call to wely-media, not one per event,
     * and one read of the caller's friendships, for who of them goes to each.
     */
    private Flux<EventResponse> respond(Flux<Event> events, String viewerId) {
        return friendshipService.viewerFriends(viewerId).flatMapMany(friends -> respond(events, friends));
    }

    private Flux<EventResponse> respond(Flux<Event> events, ViewerFriends friends) {
        return events.collectList()
                .flatMapMany(list -> pictureService.signCovers(list)
                        .flatMapIterable(urls -> list.stream()
                                .map(event -> mapper.toResponse(event, urls, friends))
                                .toList()));
    }

    private Mono<EventResponse> respond(Mono<Event> event, String viewerId) {
        return event.flatMap(found -> Mono.zip(pictureService.signCovers(List.of(found)),
                        friendshipService.viewerFriends(viewerId))
                .map(known -> mapper.toResponse(found, known.getT1(), known.getT2())));
    }
}
