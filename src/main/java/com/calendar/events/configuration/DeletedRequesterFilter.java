package com.calendar.events.configuration;

import com.calendar.events.domain.services.AccountGuard;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.exception.Problems;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Refuses every write from a deleted account with 410 EVT-BUS-025; not a bean, so it runs in the security chain only. */
@Slf4j
final class DeletedRequesterFilter implements WebFilter {

    static final Set<HttpMethod> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);
    static final String BUSINESS_ID_CLAIM = "businessId";

    private final AccountGuard accountGuard;
    private final ServerResponse.Context context;

    DeletedRequesterFilter(AccountGuard accountGuard, ServerCodecConfigurer codecs) {
        this.accountGuard = accountGuard;
        this.context = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecs.getWriters();
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (READS.contains(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        // Optional, not switchIfEmpty: refuse completes empty and would run the chain after it.
        return refusal()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(refusal -> refusal.isPresent() ? refuse(exchange, refusal.get()) : chain.filter(exchange));
    }

    /** Why the requester may not write; empty when they may, or when there is no JWT to read. */
    private Mono<EventErrorCode> refusal() {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .ofType(JwtAuthenticationToken.class)
                .mapNotNull(token -> token.getToken().getClaimAsString(BUSINESS_ID_CLAIM))
                .flatMap(accountGuard::isDeleted)
                .filter(Boolean::booleanValue)
                .map(deleted -> EventErrorCode.ACCOUNT_DELETED)
                .onErrorResume(EventException.class, error -> Mono.just(error.getErrorCode()));
    }

    private Mono<Void> refuse(ServerWebExchange exchange, EventErrorCode error) {
        String path = exchange.getRequest().getPath().value();
        log.warn("Business failure {} on {}", error.getCode(), path);
        return ServerResponse.status(error.getHttpStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(Problems.of(error, path))
                .flatMap(response -> response.writeTo(exchange, context));
    }
}
