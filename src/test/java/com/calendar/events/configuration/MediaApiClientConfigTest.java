package com.calendar.events.configuration;

import com.calendar.events.exception.RequestIds;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MediaApiClientConfigTest {

    @Test
    void mediaWebClient_shouldBeBuiltForTheConfiguredUrl() {
        // The URL, the secret header and the timeout are exercised end to end by MediaApiAdapterTest.
        assertThat(new MediaApiClientConfig().mediaWebClient("http://media:8085", "secret")).isNotNull();
        assertThat(MediaApiClientConfig.TIMEOUT).hasSeconds(10);
    }

    @Test
    void mediaWebClient_shouldRepeatTheRequestId_ofTheRequestBehindTheCall() throws Exception {
        AtomicReference<String> requestIdHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestIdHeader.set(exchange.getRequestHeaders().getFirst(RequestIds.REQUEST_ID_HEADER));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            WebClient client = new MediaApiClientConfig().mediaWebClient("http://127.0.0.1:" + server.getAddress().getPort(), "secret");
            StepVerifier.create(client.get().uri("/x").retrieve().toBodilessEntity()
                            .contextWrite(Context.of(RequestIds.REQUEST_ID_ATTRIBUTE, "abcd1234-from-gateway")))
                    .expectNextCount(1)
                    .expectComplete()
                    .verify(Duration.ofSeconds(10));
        } finally {
            server.stop(0);
        }

        assertThat(requestIdHeader).hasValue("abcd1234-from-gateway");
    }
}
