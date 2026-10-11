package com.calendar.events.infrastructure.social;

import com.calendar.events.domain.ports.FriendDirectory;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
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
public class SocialFriendDirectoryAdapter implements FriendDirectory {

    static final String FRIENDS_PATH = "/social-service/users?friendshipStatus=FRIENDS";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public SocialFriendDirectoryAdapter(WebClient socialWebClient) {
        this.webClient = socialWebClient;
    }

    @Override
    public Mono<Set<String>> findFriendIds(String userId) {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> ((JwtAuthenticationToken) context.getAuthentication()).getToken().getTokenValue())
                .flatMap(token -> webClient.get()
                        .uri(FRIENDS_PATH)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .retrieve()
                        .bodyToFlux(SocialUserResponse.class)
                        .map(SocialUserResponse::userId)
                        .collect(Collectors.toSet())
                        .timeout(TIMEOUT))
                // No token to relay means no way to ask: refuse rather than skip the check.
                .switchIfEmpty(Mono.error(new IllegalStateException("no authenticated request to relay")))
                .onErrorMap(error -> !(error instanceof EventException), error -> {
                    log.warn("Could not read the friends of {} from wely-social: {}", userId, error.getMessage());
                    return new EventException(EventErrorCode.SOCIAL_UNAVAILABLE);
                });
    }

    /** The only field this service needs from wely-social's user entries. */
    record SocialUserResponse(String userId) {
    }
}
