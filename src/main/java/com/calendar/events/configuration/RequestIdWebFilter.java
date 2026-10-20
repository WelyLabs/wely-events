package com.calendar.events.configuration;

import com.calendar.events.exception.RequestIds;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.regex.Pattern;

/**
 * Settles the id of each request before anything else runs: the gateway's {@code X-Request-Id} when it is a
 * plausible id, the WebFlux one otherwise. It is kept as an exchange attribute (problems, logs), in the Reactor
 * Context (outgoing calls to other Wely services) and sent back on the response.
 *
 * <p>The value is checked because it ends up in logs and headers: anything but a short run of letters, digits
 * and dashes could forge a log line or bloat every one of them.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdWebFilter implements WebFilter {

    static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String received = exchange.getRequest().getHeaders().getFirst(RequestIds.REQUEST_ID_HEADER);
        String requestId = received != null && VALID_ID.matcher(received).matches()
                ? received
                : exchange.getRequest().getId();
        exchange.getAttributes().put(RequestIds.REQUEST_ID_ATTRIBUTE, requestId);
        exchange.getResponse().getHeaders().set(RequestIds.REQUEST_ID_HEADER, requestId);
        return chain.filter(exchange)
                .contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, requestId));
    }
}
