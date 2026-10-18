package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ReunionResponse;
import com.calendar.events.application.mappers.FriendshipRestMapper;
import com.calendar.events.domain.services.ReunionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** "Long time no see": the caller's close friends they have drifted from. */
@RestController
@RequestMapping("events/me/reunions")
public class ReunionController {

    private final ReunionService reunionService;
    private final FriendshipRestMapper mapper;

    public ReunionController(ReunionService reunionService, FriendshipRestMapper mapper) {
        this.reunionService = reunionService;
        this.mapper = mapper;
    }

    /** Up to 3 close or intimate friends not seen for 90 days and with nothing planned, the most due first. */
    @GetMapping
    public Flux<ReunionResponse> getReunions(@AuthenticationPrincipal Jwt jwt) {
        return reunionService.reunionsFor(jwt.getClaimAsString("businessId")).map(mapper::toResponse);
    }

    /** "Later": the friend is not suggested again for 30 days. 404 EVT-BUS-036 for anyone but a friend. */
    @PostMapping("/{friendId}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> dismiss(@PathVariable String friendId, @AuthenticationPrincipal Jwt jwt) {
        return reunionService.dismiss(jwt.getClaimAsString("businessId"), friendId);
    }
}
