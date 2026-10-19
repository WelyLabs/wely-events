package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.InvitationRequest;
import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.services.InvitationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Invitations to an event, one friend at a time: the organizer invites or takes back, the invitee declines. */
@RestController
@RequestMapping("events")
public class InvitationController {

    private final InvitationService invitationService;
    private final EventRestMapper mapper;

    public InvitationController(InvitationService invitationService, EventRestMapper mapper) {
        this.invitationService = invitationService;
        this.mapper = mapper;
    }

    /**
     * Invites a friend: who takes part and is invited, as the organizer sees it. 403 EVT-BUS-002 for
     * anyone but the organizer, 404 EVT-BUS-036 for someone not their friend, 409 EVT-BUS-039 on a
     * community event, one that is over, or for whoever it is hidden from, 409 EVT-BUS-040 for someone
     * already taking part.
     */
    @PostMapping("/{id}/invitations")
    public Mono<ParticipantsResponse> invite(@PathVariable String id, @Valid @RequestBody InvitationRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return invitationService.invite(id, userId, request.userId()).map(mapper::toResponse);
    }

    /** The organizer takes an invitation back, or the invitee declines it; 403 for anyone else. */
    @DeleteMapping("/{id}/invitations/{inviteeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> withdraw(@PathVariable String id, @PathVariable String inviteeId,
                               @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return invitationService.withdraw(id, userId, inviteeId);
    }
}
