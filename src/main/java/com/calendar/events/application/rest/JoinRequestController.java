package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.ports.SocialGraph;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * The organizer answers who asked to join their surprise. Asking happens through
 * {@code POST /events/{id}/subscribe}, which records a request instead of joining when the asker is
 * only a plain friend of the honoree.
 */
@RestController
@RequestMapping("events")
public class JoinRequestController {

    private static final String BUSINESS_ID = "businessId";

    private final EventService eventService;
    private final SocialGraph socialGraph;
    private final EventRestMapper mapper;

    public JoinRequestController(EventService eventService, SocialGraph socialGraph, EventRestMapper mapper) {
        this.eventService = eventService;
        this.socialGraph = socialGraph;
        this.mapper = mapper;
    }

    /**
     * Accepts a request: the asker takes part from then on. 403 EVT-BUS-002 for anyone but the
     * organizer, 404 EVT-BUS-042 for a request withdrawn or already answered.
     */
    @PostMapping("/{id}/requests/{userId}")
    public Mono<ParticipantsResponse> accept(@PathVariable String id, @PathVariable String userId,
                                             @AuthenticationPrincipal Jwt jwt) {
        String organizerId = jwt.getClaimAsString(BUSINESS_ID);
        return eventService.acceptJoinRequest(id, organizerId, userId).flatMap(event -> respond(event, organizerId));
    }

    /** Declines a request: it goes, and the asker may ask again. */
    @DeleteMapping("/{id}/requests/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> decline(@PathVariable String id, @PathVariable String userId,
                              @AuthenticationPrincipal Jwt jwt) {
        return eventService.declineJoinRequest(id, jwt.getClaimAsString(BUSINESS_ID), userId).then();
    }

    /** The answer shows who takes part and who is still waiting, as the organizer sees it. */
    private Mono<ParticipantsResponse> respond(Event event, String organizerId) {
        return socialGraph.friendIdsOf(organizerId)
                .defaultIfEmpty(Set.of())
                .map(friendIds -> mapper.toResponse(Participants.seenBy(event, organizerId, friendIds)));
    }
}
