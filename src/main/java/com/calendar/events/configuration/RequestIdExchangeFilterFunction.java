package com.calendar.events.configuration;

import com.calendar.events.exception.RequestIds;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * Repeats the current request's id on a call to another Wely service, so that service logs it under the same
 * id. Only for Wely services: an external party has no use for it. A call with no request behind it — a relay,
 * the reconciliation loop — has no id in its context and goes without.
 */
final class RequestIdExchangeFilterFunction implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(context -> next.exchange(context.<String>getOrEmpty(RequestIds.REQUEST_ID_ATTRIBUTE)
                .map(requestId -> ClientRequest.from(request).header(RequestIds.REQUEST_ID_HEADER, requestId).build())
                .orElse(request)));
    }
}
