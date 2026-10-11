package com.calendar.events.infrastructure.social;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.calendar.events.configuration.SocialClientConfig;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SocialFriendDirectoryAdapterTest {

    private HttpServer server;
    private SocialFriendDirectoryAdapter adapter;
    private final AtomicReference<String> requestedUri = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "[{\"userId\":\"lea\",\"userName\":\"lea\",\"hashtag\":6000},{\"userId\":\"hugo\"}]";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedUri.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        adapter = new SocialFriendDirectoryAdapter(new SocialClientConfig()
                .socialWebClient("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static JwtAuthenticationToken token(String value) {
        return new JwtAuthenticationToken(Jwt.withTokenValue(value).header("alg", "none").claim("businessId", "me").build());
    }

    @Test
    void findFriendIds_shouldRelayTheRequestersTokenAndReadTheIds() {
        StepVerifier.create(adapter.findFriendIds("me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("abc.def.ghi"))))
                .expectNext(Set.of("lea", "hugo"))
                .verifyComplete();

        assertThat(requestedUri.get()).isEqualTo(SocialFriendDirectoryAdapter.FRIENDS_PATH);
        assertThat(authorization.get()).isEqualTo("Bearer abc.def.ghi");
    }

    @Test
    void findFriendIds_shouldReportAnOutage_whenSocialFails() {
        status = 502;

        StepVerifier.create(adapter.findFriendIds("me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("t"))))
                .expectErrorMatches(e -> e instanceof EventException ex
                        && ex.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE)
                .verify();
    }

    @Test
    void findFriendIds_shouldRefuse_whenThereIsNoTokenToRelay() {
        StepVerifier.create(adapter.findFriendIds("me"))
                .expectErrorMatches(e -> e instanceof EventException ex
                        && ex.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE)
                .verify();

        assertThat(requestedUri.get()).isNull();
    }
}
