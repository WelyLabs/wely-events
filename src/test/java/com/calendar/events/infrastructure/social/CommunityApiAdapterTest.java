package com.calendar.events.infrastructure.social;

import com.calendar.events.configuration.SocialClientConfig;
import com.calendar.events.domain.models.CommunityRole;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityApiAdapterTest {

    private HttpServer server;
    private CommunityApiAdapter adapter;
    private final AtomicReference<String> requestedUri = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"communityId\":\"c1\",\"name\":\"Club\",\"role\":\"ADMIN\"}";

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
        adapter = new CommunityApiAdapter(new SocialClientConfig()
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
    void roleOf_shouldRelayTheRequestersTokenAndReadTheRole() {
        StepVerifier.create(adapter.roleOf("c1", "me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("abc.def"))))
                .expectNext(CommunityRole.ADMIN)
                .verifyComplete();

        assertThat(requestedUri.get()).isEqualTo("/social-service/communities/c1");
        assertThat(authorization.get()).isEqualTo("Bearer abc.def");
    }

    @Test
    void roleOf_shouldBeEmpty_forAPublicCommunityTheUserIsNotIn() {
        body = "{\"communityId\":\"c1\",\"role\":null}";

        StepVerifier.create(adapter.roleOf("c1", "me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("t"))))
                .verifyComplete();
    }

    @Test
    void roleOf_shouldBeEmpty_whenSocialAnswersNotFound() {
        // A private community seen from outside reads exactly as one that does not exist.
        status = 404;
        body = "{\"code\":\"SCL-BUS-010\"}";

        StepVerifier.create(adapter.roleOf("c1", "me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("t"))))
                .verifyComplete();
    }

    @Test
    void roleOf_shouldReportAnOutage_whenSocialFails() {
        status = 502;

        StepVerifier.create(adapter.roleOf("c1", "me")
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(token("t"))))
                .expectErrorMatches(e -> e instanceof TechnicalException ex
                        && ex.getErrorCode() == TechnicalErrorCode.SOCIAL_UNAVAILABLE
                        && ex.getCause() instanceof WebClientResponseException response
                        && response.getStatusCode().value() == 502)
                .verify();
    }

    @Test
    void roleOf_shouldRefuse_whenThereIsNoTokenToRelay() {
        StepVerifier.create(adapter.roleOf("c1", "me"))
                .expectErrorMatches(e -> e instanceof TechnicalException ex
                        && ex.getErrorCode() == TechnicalErrorCode.SOCIAL_UNAVAILABLE
                        && ex.getCause() instanceof IllegalStateException)
                .verify();

        assertThat(requestedUri.get()).isNull();
    }
}
