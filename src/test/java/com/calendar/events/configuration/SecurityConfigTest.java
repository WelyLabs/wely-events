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
     * Le port est mocké pour que ce test ne porte que sur la sécurité : sans cela, une
     * requête autorisée atteint un MongoDB absent et le test expire au lieu d'échouer.
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
    @DisplayName("sans token, une route métier répond 401 et non 200")
    void springSecurityFilterChain_shouldRejectUnauthenticatedRequests() {
        client().get().uri("/events-service/events/me/feed")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("la suppression n'est plus atteignable sans authentification")
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
    @DisplayName("le préflight CORS reste ouvert, sinon le navigateur bloque tout")
    void springSecurityFilterChain_shouldAllowCorsPreflight() {
        client().options().uri("/events-service/events")
                .header("Origin", "http://localhost:4200")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().is2xxSuccessful();
    }

    @Test
    @DisplayName("les probes de santé répondent sans token, sinon le kubelet voit 401")
    void springSecurityFilterChain_shouldExposeHealthProbesAnonymously() {
        // Le kubelet ne porte pas de JWT. Si ces deux chemins exigeaient une
        // authentification, la liveness échouerait en boucle et Kubernetes
        // redémarrerait des pods parfaitement sains.
        client().get().uri("/actuator/health/liveness")
                .exchange()
                .expectStatus().isOk();

        client().get().uri("/actuator/health/readiness")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("le reste d'actuator n'est pas ouvert pour autant")
    void springSecurityFilterChain_shouldNotExposeTheRestOfActuator() {
        // /actuator/health tout court n'est pas dans la liste blanche, et les endpoints
        // qui décrivent les internes ne sont même pas exposés.
        client().get().uri("/actuator/env")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void springSecurityFilterChain_shouldBeTheOnlyChainDeclared() {
        // Une seconde chaîne plus permissive annulerait silencieusement celle-ci.
        assertThat(context.getBeansOfType(
                org.springframework.security.web.server.SecurityWebFilterChain.class)).hasSize(1);
    }
}
