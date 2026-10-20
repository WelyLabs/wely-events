package com.calendar.events.configuration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.calendar.events.domain.ports.CalendarLinks;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.RequestIds;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

/** The security chain: no endpoint without a token, and no write from a deleted account. */
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

    /** Mocked for the same reason: the feed reads the caller's friends from the projection. */
    @MockitoBean
    private FriendshipDirectory friendships;

    /** The deleted-accounts register, read on every write. */
    @MockitoBean
    private DeletedUserMarkers markers;

    /** The calendar links, read when a calendar app opens one. */
    @MockitoBean
    private CalendarLinks calendarLinks;

    private WebTestClient client() {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(30))
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
    @DisplayName("the 401 is our problem, and still carries the bearer challenge")
    void springSecurityFilterChain_shouldAnswerOurProblem_whenUnauthenticated() {
        client().get().uri("/events-service/events/me/feed")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "^Bearer.*")
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(BusinessErrorCode.AUTHENTICATION_REQUIRED.getCode())
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo("/events-service/events/me/feed")
                .jsonPath("$.requestId").isNotEmpty();
    }

    @Test
    @DisplayName("the 401 carries the gateway's request id, like every other problem")
    void springSecurityFilterChain_shouldAnswerWithTheGatewaysRequestId() {
        client().get().uri("/events-service/events/me/feed")
                .header(RequestIds.REQUEST_ID_HEADER, "abcd1234-gateway-401")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(RequestIds.REQUEST_ID_HEADER, "abcd1234-gateway-401")
                .expectBody()
                .jsonPath("$.requestId").isEqualTo("abcd1234-gateway-401");
    }

    @Test
    @DisplayName("Prometheus scrapes without a token, and finds the technical failures counted by code")
    void springSecurityFilterChain_shouldExposePrometheusAnonymously() {
        // One technical failure first: the register is down when a write checks it.
        when(markers.isDeleted("lea")).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE)));
        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "lea")))
                .post().uri("/events-service/events/e1/subscribe")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);

        client().get().uri("/actuator/prometheus")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .contains("wely_errors_technical_total{code=\"EVT-TEC-005\",source=\"http\"}")
                        // The HTTP latency SLO buckets, after that request.
                        .containsPattern("http_server_requests_seconds_bucket\\{[^}]*le=\"0\\.25\"")
                        // The product metrics, registered at zero before the first fact, under their real names.
                        .contains("wely_events_creations_total ")
                        .contains("wely_events_invitations_sent_total ")
                        .contains("wely_events_poll_creations_total ")
                        .contains("wely_events_polls_votes_total "));
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
        when(friendships.friendsOf(any())).thenReturn(Flux.empty());
        when(eventRepository.findFeed(any(), any())).thenReturn(Flux.empty());

        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "user-1")))
                .get().uri("/events-service/events/me/feed")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("a deleted account's still-valid token can no longer write: 410, in the problem shape")
    void springSecurityFilterChain_shouldRefuseWritesFromADeletedAccount_with410() {
        when(markers.isDeleted("gone")).thenReturn(Mono.just(true));

        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "gone")))
                .post().uri("/events-service/events/e1/subscribe")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GONE)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-025")
                .jsonPath("$.instance").isEqualTo("/events-service/events/e1/subscribe");
        verify(eventRepository, never()).findShownTo(any(String.class), any(String.class));
    }

    @Test
    void springSecurityFilterChain_shouldServeReadsToADeletedAccount() {
        when(friendships.friendsOf(any())).thenReturn(Flux.empty());
        when(eventRepository.findFeed(any(), any())).thenReturn(Flux.empty());

        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "gone")))
                .get().uri("/events-service/events/me/feed")
                .exchange()
                .expectStatus().isOk();
        verify(markers, never()).isDeleted(any());
    }

    @Test
    void springSecurityFilterChain_shouldLetWritesThrough_forALiveAccount() {
        when(markers.isDeleted("lea")).thenReturn(Mono.just(false));
        when(eventRepository.findShownTo("e1", "lea")).thenReturn(Mono.empty());

        client().mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "lea")))
                .post().uri("/events-service/events/e1/subscribe")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-001");
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
    @DisplayName("a calendar app reads an event's .ics without a token, through its link")
    void springSecurityFilterChain_shouldServeCalendarFilesAnonymously() {
        when(calendarLinks.findByToken("tok")).thenReturn(Mono.empty());

        // Past security: the unknown link is the service's own 404, not a 401.
        client().get().uri("/events-service/public/calendar/tok.ics")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("only reading a calendar file is opened, and asking for a link still needs a token")
    void springSecurityFilterChain_shouldKeepTheRestOfCalendarClosed() {
        client().post().uri("/events-service/public/calendar/tok.ics")
                .exchange()
                .expectStatus().isUnauthorized();

        client().post().uri("/events-service/events/e1/calendar-link")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the rest of actuator is not opened along with it")
    void springSecurityFilterChain_shouldNotExposeTheRestOfActuator() {
        // Plain /actuator/health is not on the allow-list, and the endpoints that
        // describe the internals are not even exposed.
        client().get().uri("/actuator/env")
                .exchange()
                .expectStatus().isUnauthorized();

        // Not exposed at all: even with a token, there is nothing there.
        client().mutateWith(mockJwt())
                .get().uri("/actuator/env")
                .exchange()
                .expectStatus().isNotFound();

        // Opening prometheus opened a GET, not the path for every method.
        client().post().uri("/actuator/prometheus")
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
