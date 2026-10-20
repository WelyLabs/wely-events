package com.calendar.events.configuration;

import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.ProblemWriter;
import com.calendar.events.exception.Problems;
import com.calendar.events.exception.RequestIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers a request without a valid token with our 401 problem, {@code EVT-REQ-001}.
 *
 * <p>The {@code WWW-Authenticate: Bearer ...} header is still the resource server's own: its entry point
 * runs first, on a response it cannot complete, and only the body is ours.
 */
@Slf4j
final class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final ServerAuthenticationEntryPoint bearerHeaders = new BearerTokenServerAuthenticationEntryPoint();
    private final ProblemWriter problemWriter;

    ProblemAuthenticationEntryPoint(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        BusinessErrorCode error = BusinessErrorCode.AUTHENTICATION_REQUIRED;
        String path = exchange.getRequest().getPath().value();
        String requestId = RequestIds.of(exchange);
        return bearerHeaders.commence(keepingResponseOpen(exchange), ex)
                .then(Mono.defer(() -> {
                    log.warn("Business failure {} on {} (request {})", error.getCode(), path, requestId);
                    return problemWriter.write(exchange, Problems.of(error, path, requestId));
                }));
    }

    /** The same exchange, on a response a delegate can set headers on but not complete: the body comes after. */
    static ServerWebExchange keepingResponseOpen(ServerWebExchange exchange) {
        return exchange.mutate()
                .response(new ServerHttpResponseDecorator(exchange.getResponse()) {
                    @Override
                    public Mono<Void> setComplete() {
                        return Mono.empty();
                    }
                })
                .build();
    }
}
