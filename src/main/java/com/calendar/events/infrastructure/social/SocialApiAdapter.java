package com.calendar.events.infrastructure.social;

import com.calendar.events.domain.ports.FriendDirectory;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import lombok.extern.slf4j.Slf4j;
import com.calendar.events.domain.models.Closeness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the requester's friends from wely-social, on the requester's own token: wely-social
 * answers "my friends" for whoever the token names, so no service account is needed and the
 * answer cannot be about anyone else.
 */
@Slf4j
@Component
public class SocialApiAdapter implements FriendDirectory {

    static final String FRIENDS_PATH = "/social-service/users?friendshipStatus=FRIENDS";
    static final String FRIENDSHIP_PATH = "/social-service/relationships/{friendId}";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public SocialApiAdapter(WebClient socialWebClient) {
        this.webClient = socialWebClient;
    }

    @Override
    public Mono<Set<String>> findFriendIds(String userId) {
        return bearerToken()
                .flatMap(token -> webClient.get()
                        .uri(FRIENDS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .retrieve()
                        .bodyToFlux(SocialUserResponse.class)
                        .map(SocialUserResponse::userId)
                        .collect(Collectors.toSet())
                        .timeout(TIMEOUT))
                .onErrorMap(error -> !(error instanceof EventException),
                        error -> unavailable("the friends of " + userId, error));
    }

    /** wely-social answers 404 when the two are not friends: that is an empty answer, not a failure. */
    @Override
    public Mono<Closeness> findCloseness(String userId, String friendId) {
        return bearerToken()
                .flatMap(token -> webClient.get()
                        .uri(FRIENDSHIP_PATH, friendId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .exchangeToMono(response -> {
                            if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                                return response.releaseBody().then(Mono.<SocialFriendshipResponse>empty());
                            }
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(SocialFriendshipResponse.class);
                            }
                            return response.createError();
                        })
                        .map(friendship -> Closeness.valueOf(friendship.closeness()))
                        .timeout(TIMEOUT))
                .onErrorMap(error -> !(error instanceof EventException),
                        error -> unavailable("the friendship between " + userId + " and " + friendId, error));
    }

    /** The caller's own token, relayed: no token means no way to ask, so refuse rather than skip the check. */
    private static Mono<String> bearerToken() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> ((JwtAuthenticationToken) context.getAuthentication()).getToken().getTokenValue())
                .map(token -> "Bearer " + token)
                .switchIfEmpty(Mono.error(new IllegalStateException("no authenticated request to relay")));
    }

    private static EventException unavailable(String what, Throwable error) {
        log.warn("Could not read {} from wely-social: {}", what, error.getMessage());
        return new EventException(EventErrorCode.SOCIAL_UNAVAILABLE);
    }

    /** The only field this service needs from wely-social's user entries. */
    record SocialUserResponse(String userId) {
    }

    /** The only field this service needs from a wely-social friendship. */
    record SocialFriendshipResponse(String closeness) {
    }
}
