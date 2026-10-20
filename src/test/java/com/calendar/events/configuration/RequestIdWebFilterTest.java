package com.calendar.events.configuration;

import com.calendar.events.exception.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdWebFilterTest {

    private static final String GATEWAY_ID = "3f0c2a4e-8b1d-4c6a-9e2f-1a2b3c4d5e6f";

    private final RequestIdWebFilter filter = new RequestIdWebFilter();
    private final AtomicReference<String> seenInContext = new AtomicReference<>();
    private final WebFilterChain chain = exchange -> Mono.deferContextual(context -> {
        seenInContext.set(context.<String>getOrEmpty(RequestIds.REQUEST_ID_ATTRIBUTE).orElse(null));
        return Mono.empty();
    });

    private MockServerWebExchange run(String header) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/events-service/events/me/feed");
        if (header != null) {
            request.header(RequestIds.REQUEST_ID_HEADER, header);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        StepVerifier.create(filter.filter(exchange, chain)).expectComplete().verify(Duration.ofSeconds(10));
        return exchange;
    }

    private void assertIdIs(MockServerWebExchange exchange, String expected) {
        assertThat(RequestIds.of(exchange)).isEqualTo(expected);
        assertThat(seenInContext).hasValue(expected);
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIds.REQUEST_ID_HEADER)).isEqualTo(expected);
    }

    @Test
    void filter_shouldKeepTheGatewaysId_whenItIsValid() {
        assertIdIs(run(GATEWAY_ID), GATEWAY_ID);
    }

    @Test
    void filter_shouldFallBackToTheWebFluxId_whenTheHeaderIsMissing() {
        MockServerWebExchange exchange = run(null);

        assertIdIs(exchange, exchange.getRequest().getId());
    }

    @Test
    void filter_shouldFallBackToTheWebFluxId_whenTheHeaderIsNotAPlausibleId() {
        for (String invalid : new String[]{"abc\\nERROR forged line", "short-1", "a".repeat(65), "spaces in id", ""}) {
            MockServerWebExchange exchange = run(invalid);

            assertIdIs(exchange, exchange.getRequest().getId());
        }
    }

    @Test
    void filter_shouldAcceptTheShortestAndLongestIds() {
        assertIdIs(run("abcd1234"), "abcd1234");
        String longest = "A-".repeat(32);
        assertIdIs(run(longest), longest);
    }
}
