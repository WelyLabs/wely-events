package com.calendar.events.configuration;

import com.calendar.events.exception.RequestIds;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdExchangeFilterFunctionTest {

    private final RequestIdExchangeFilterFunction filter = new RequestIdExchangeFilterFunction();
    private final AtomicReference<ClientRequest> sent = new AtomicReference<>();
    private final ExchangeFunction next = request -> {
        sent.set(request);
        return Mono.just(ClientResponse.create(HttpStatus.OK).build());
    };
    private final ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://social/x")).build();

    @Test
    void filter_shouldRepeatTheRequestIdOfTheContext() {
        StepVerifier.create(filter.filter(request, next)
                        .contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-from-gateway")))
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(sent.get().headers().getFirst(RequestIds.REQUEST_ID_HEADER)).isEqualTo("abcd1234-from-gateway");
    }

    @Test
    void filter_shouldSendNoId_whenNoRequestIsBehindTheCall() {
        StepVerifier.create(filter.filter(request, next))
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(sent.get()).isSameAs(request);
        assertThat(sent.get().headers().containsHeader(RequestIds.REQUEST_ID_HEADER)).isFalse();
    }
}
