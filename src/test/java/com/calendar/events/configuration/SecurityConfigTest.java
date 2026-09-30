package com.calendar.events.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.calendar.events.domain.ports.EventRepository;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/**
 * Guards the fix for the service's worst defect: the only filter chain used to end in
 * {@code anyExchange().permitAll()}, leaving every endpoint — event deletion included —
 * reachable without a token.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SecurityConfigTest {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    /**
     * The port is mocked so this test covers security rather than persistence: without it
     * an authorised request reaches an absent MongoDB and the test times out instead of
     * failing.
     */
    @MockitoBean
    private EventRepository eventRepository;

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .build();
    }

    @Test
    @DisplayName("without a token, a business route answers 401 rather than 200")
    void springSecurityFilterChain_shouldRejectUnauthenticatedRequests() {
        client().get().uri("/events-service/events/me/feed")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("deletion is no longer reachable without authentication")
    void springSecurityFilterChain_shouldRejectUnauthenticatedDeletion() {
        client().delete().uri("/events-service/events/some-id")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void springSecurityFilterChain_shouldAllowAuthenticatedRequests() {
        when(eventRepository.findByParticipantIdNot(any())).thenReturn(Flux.empty());

        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "user-1")))
                .get().uri("/events-service/events/me/feed")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the CORS preflight stays open, or the browser blocks everything")
    void springSecurityFilterChain_shouldAllowCorsPreflight() {
        client().options().uri("/events-service/events")
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    @DisplayName("the health probes answer without a token, or the kubelet sees 401")
    void springSecurityFilterChain_shouldExposeHealthProbesAnonymously() {
        // The kubelet carries no JWT. Were these two paths to require authentication,
        // liveness would fail in a loop and Kubernetes would restart perfectly
        // healthy pods.
        client().get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("the rest of actuator is not opened along with it")
    void springSecurityFilterChain_shouldNotExposeTheRestOfActuator() {
        // Plain /actuator/health is not on the allow-list, and the endpoints that
        // describe the internals are not even exposed.
        client().get().uri("/actuator/env")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void springSecurityFilterChain_shouldBeTheOnlyChainDeclared() {
        // A second, more permissive chain would silently override this one.
        assertThat(context.getBeansOfType(
                org.springframework.security.web.server.SecurityWebFilterChain.class)).hasSize(1);
    }
}
