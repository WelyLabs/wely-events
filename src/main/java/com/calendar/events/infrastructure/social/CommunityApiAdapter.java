package com.calendar.events.infrastructure.social;

import com.calendar.events.domain.models.CommunityRole;
import com.calendar.events.domain.ports.CommunityDirectory;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Reads the requester's role in a community from wely-social, on the requester's own token, as
 * {@link SocialApiAdapter} does for friends: wely-social answers for whoever the token names, so
 * the answer cannot be about anyone else. {@code userId} is therefore only used in logs.
 */
@Slf4j
@Component
public class CommunityApiAdapter implements CommunityDirectory {

    static final String COMMUNITY_PATH = "/social-service/communities/{communityId}";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;

    public CommunityApiAdapter(WebClient socialWebClient) {
        this.webClient = socialWebClient;
    }

    /** 404 is a private community seen from outside, or none at all: either way, not a member. */
    @Override
    public Mono<CommunityRole> roleOf(String communityId, String userId) {
        return bearerToken()
                .flatMap(token -> webClient.get()
                        .uri(COMMUNITY_PATH, communityId)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .exchangeToMono(response -> {
                            if (response.statusCode().value() == HttpStatus.NOT_FOUND.value()) {
                                return response.releaseBody().then(Mono.<SocialCommunityResponse>empty());
                            }
                            if (response.statusCode().is2xxSuccessful()) {
                                return response.bodyToMono(SocialCommunityResponse.class);
                            }
                            return response.createError();
                        })
                        .timeout(TIMEOUT))
                .onErrorMap(error -> !(error instanceof TechnicalException), error -> {
                    log.warn("Could not read the role of {} in community {} from wely-social: {}",
                            userId, communityId, error.getMessage());
                    return new TechnicalException(TechnicalErrorCode.SOCIAL_UNAVAILABLE, error);
                })
                .flatMap(community -> community.role() == null
                        ? Mono.empty()
                        : Mono.just(CommunityRole.valueOf(community.role())));
    }

    /** The caller's own token, relayed: no token means no way to ask, so refuse rather than skip the check. */
    private static Mono<String> bearerToken() {
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> ((JwtAuthenticationToken) context.getAuthentication()).getToken().getTokenValue())
                .map(token -> "Bearer " + token)
                .switchIfEmpty(Mono.error(new IllegalStateException("no authenticated request to relay")));
    }

    /** The only field this service needs from a wely-social community: null when not a member. */
    record SocialCommunityResponse(String role) {
    }
}
