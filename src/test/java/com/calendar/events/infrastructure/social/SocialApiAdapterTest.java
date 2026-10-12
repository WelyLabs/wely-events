package com.calendar.events.infrastructure.social;

import com.calendar.events.domain.models.Closeness;
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

class SocialApiAdapterTest {

    private HttpServer server;
    private SocialApiAdapter adapter;
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
        adapter = new SocialApiAdapter(new SocialClientConfig()
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
    void friendIdsOf_shouldRelayTheRequestersTokenAndReadTheIds() {
        StepVerifier.create(adapter.friendIdsOf("me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("abc.def.ghi"))))
                .expectNext(Set.of("lea", "hugo"))
                .verifyComplete();

        assertThat(requestedUri.get()).isEqualTo(SocialApiAdapter.FRIENDS_PATH);
        assertThat(authorization.get()).isEqualTo("Bearer abc.def.ghi");
    }

    @Test
    void friendIdsOf_shouldReportAnOutage_whenSocialFails() {
        status = 502;

        StepVerifier.create(adapter.friendIdsOf("me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("t"))))
                .expectErrorMatches(e -> e instanceof EventException ex
                        && ex.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE)
                .verify();
    }

    @Test
    void friendIdsOf_shouldRefuse_whenThereIsNoTokenToRelay() {
        StepVerifier.create(adapter.friendIdsOf("me"))
                .expectErrorMatches(e -> e instanceof EventException ex
                        && ex.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE)
                .verify();

        assertThat(requestedUri.get()).isNull();
    }

    @Test
    void closenessBetween_shouldReadTheClosenessOfTheFriendship() {
        body = "{\"friendId\":\"bob\",\"closeness\":\"INTIMATE\",\"proposal\":null,\"proposedByFriend\":false}";

        StepVerifier.create(adapter.closenessBetween("me", "bob")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("abc"))))
                .expectNext(Closeness.INTIMATE)
                .verifyComplete();

        assertThat(requestedUri.get()).isEqualTo("/social-service/relationships/bob");
        assertThat(authorization.get()).isEqualTo("Bearer abc");
    }

    @Test
    void closenessBetween_shouldBeEmpty_whenSocialSaysTheyAreNotFriends() {
        status = 404;
        body = "{\"code\":\"SCL-BUS-007\"}";

        StepVerifier.create(adapter.closenessBetween("me", "carol")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("t"))))
                .verifyComplete();
    }

    @Test
    void closenessBetween_shouldReportAnOutage_whenSocialFails() {
        status = 500;

        StepVerifier.create(adapter.closenessBetween("me", "bob")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("t"))))
                .expectErrorMatches(e -> e instanceof EventException ex
                        && ex.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE)
                .verify();
    }

    @Test
    void closenessBetween_shouldRefuse_whenThereIsNoTokenToRelay() {
        StepVerifier.create(adapter.closenessBetween("me", "bob"))
                .expectErrorMatches(e -> e instanceof EventException ex
                        && ex.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE)
                .verify();
    }
}
