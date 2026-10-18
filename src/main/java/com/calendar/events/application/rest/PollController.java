package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.BallotRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.FixDateRequest;
import com.calendar.events.application.dtos.PollResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.PollService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/** Date polls: vote, fix the date, list the open ones, and follow the votes live over SSE. */
@RestController
@RequestMapping("events")
public class PollController {

    static final String POLL_EVENT = "poll";
    static final String KEEP_ALIVE_COMMENT = "keep-alive";

    private final PollService pollService;
    private final PictureService pictureService;
    private final FriendshipService friendshipService;
    private final EventRestMapper mapper;
    private final Duration keepAlive;

    public PollController(PollService pollService, PictureService pictureService, FriendshipService friendshipService,
                          EventRestMapper mapper, @Value("${app.poll.keep-alive:20s}") Duration keepAlive) {
        this.pollService = pollService;
        this.pictureService = pictureService;
        this.friendshipService = friendshipService;
        this.mapper = mapper;
        this.keepAlive = keepAlive;
    }

    /** Open polls the caller organizes, takes part in or voted on. */
    @GetMapping("/me/polls")
    public Flux<EventResponse> getMyPolls(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(pollService.getMyPolls(userId), userId);
    }

    /** Replaces the caller's ballot. 404 if the event is not theirs to see, 409 once fixed, 400 on an unknown slot. */
    @PutMapping("/{id}/poll/ballot")
    public Mono<PollResponse> castBallot(@PathVariable String id, @Valid @RequestBody BallotRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return pollService.vote(id, userId, request.answers()).map(mapper::toResponse);
    }

    /** Organizer only: fixes the date on a slot and subscribes its yes voters. */
    @PostMapping("/{id}/poll/fix")
    public Mono<EventResponse> fixDate(@PathVariable String id, @Valid @RequestBody FixDateRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return respond(pollService.fix(id, userId, request.slotId()), userId);
    }

    /**
     * The poll now, then each change, as {@code poll} events; ends after the closed state. Comments
     * keep the connection busy for proxies, and nginx is told not to buffer it.
     */
    @GetMapping(value = "/{id}/poll/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<PollResponse>>>> streamPoll(@PathVariable String id,
                                                                                @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return pollService.watch(id, userId)
                .map(states -> ResponseEntity.ok()
                        .header("X-Accel-Buffering", "no")
                        .body(withKeepAlive(states.map(poll -> ServerSentEvent.builder(mapper.toResponse(poll))
                                .event(POLL_EVENT)
                                .build()))));
    }

    // The keep-alive stops with the states, so the response completes when the poll closes.
    private Flux<ServerSentEvent<PollResponse>> withKeepAlive(Flux<ServerSentEvent<PollResponse>> states) {
        return states.publish(shared -> Flux.merge(shared,
                Flux.interval(keepAlive)
                        .map(tick -> ServerSentEvent.<PollResponse>builder().comment(KEEP_ALIVE_COMMENT).build())
                        .takeUntilOther(shared.then())));
    }

    /** One signing round and one read of the caller's friends for the whole list, as in EventController. */
    private Flux<EventResponse> respond(Flux<Event> events, String viewerId) {
        return events.collectList()
                .flatMapMany(list -> Mono.zip(pictureService.signCovers(list), friendshipService.viewerFriends(viewerId))
                        .flatMapIterable(known -> list.stream()
                                .map(event -> mapper.toResponse(event, known.getT1(), known.getT2()))
                                .toList()));
    }

    private Mono<EventResponse> respond(Mono<Event> event, String viewerId) {
        return respond(event.flux(), viewerId).next();
    }
}
