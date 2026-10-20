package com.calendar.events.configuration;

import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.ProblemWriter;
import com.calendar.events.exception.Problems;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.server.BearerTokenServerAccessDeniedHandler;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers an authenticated request the chain refuses with our 403 problem, {@code EVT-REQ-002}, keeping the
 * resource server's {@code WWW-Authenticate} header.
 *
 * <p>No rule of this service refuses an authenticated caller today — every route is either open or
 * {@code authenticated()} — so this guards the shape of a rule added later.
 */
@Slf4j
final class ProblemAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final ServerAccessDeniedHandler bearerHeaders = new BearerTokenServerAccessDeniedHandler();
    private final ProblemWriter problemWriter;

    ProblemAccessDeniedHandler(ProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
        BusinessErrorCode error = BusinessErrorCode.ACCESS_DENIED;
        String path = exchange.getRequest().getPath().value();
        String requestId = exchange.getRequest().getId();
        return bearerHeaders.handle(ProblemAuthenticationEntryPoint.keepingResponseOpen(exchange), denied)
                .then(Mono.defer(() -> {
                    log.warn("Business failure {} on {} (request {})", error.getCode(), path, requestId);
                    return problemWriter.write(exchange, Problems.of(error, path, requestId));
                }));
    }
}
