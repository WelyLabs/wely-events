package com.calendar.events.configuration;

import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.services.AccountGuard;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeletedRequesterFilterTest {

    @Mock
    private DeletedUserMarkers markers;

    private DeletedRequesterFilter filter;
    private final AtomicBoolean chainRan = new AtomicBoolean();
    private final WebFilterChain chain = exchange -> Mono.fromRunnable(() -> chainRan.set(true));

    @BeforeEach
    void setUp() {
        filter = new DeletedRequesterFilter(new AccountGuard(markers), ServerCodecConfigurer.create());
    }

    private static JwtAuthenticationToken tokenOf(String businessId) {
        Jwt.Builder jwt = Jwt.withTokenValue("token").header("alg", "none").subject("kc-1")
                .issuedAt(Instant.parse("2026-10-15T14:00:00Z")).expiresAt(Instant.parse("2026-10-15T14:05:00Z"));
        if (businessId != null) {
            jwt.claim("businessId", businessId);
        }
        return new JwtAuthenticationToken(jwt.build());
    }

    private Mono<Void> run(MockServerWebExchange exchange, Authentication authentication) {
        Mono<Void> filtered = filter.filter(exchange, chain);
        return authentication == null ? filtered
                : filtered.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication));
    }

    private static MockServerWebExchange post() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/events-service/events/e1/subscribe"));
    }

    @Test
    void filter_shouldLetReadsThrough_withoutLookingUpTheMarker() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/events-service/events/me/feed"));

        StepVerifier.create(run(exchange, tokenOf("gone"))).verifyComplete();

        assertThat(chainRan).isTrue();
        verify(markers, never()).isDeleted(anyString());
    }

    @Test
    void filter_shouldLetAWriteThrough_whenTheRequesterIsNotDeleted() {
        when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

        StepVerifier.create(run(post(), tokenOf("lea"))).verifyComplete();

        assertThat(chainRan).isTrue();
    }

    @Test
    void filter_shouldAnswerGoneWithAProblemDetail_whenTheRequesterIsDeleted() {
        when(markers.isDeleted("gone")).thenReturn(Mono.just(true));
        MockServerWebExchange exchange = post();

        StepVerifier.create(run(exchange, tokenOf("gone"))).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"EVT-BUS-025\"")
                        .contains("\"status\":410")
                        .contains("\"instance\":\"/events-service/events/e1/subscribe\""))
                .verifyComplete();
    }

    @Test
    void filter_shouldNotRunTheChain_afterRefusing() {
        when(markers.isDeleted("gone")).thenReturn(Mono.just(true));

        StepVerifier.create(run(post(), tokenOf("gone"))).verifyComplete();

        assertThat(chainRan).isFalse();
    }

    @Test
    void filter_shouldLetAnExchangeWithoutJwtThrough() {
        StepVerifier.create(run(post(), null)).verifyComplete();
        assertThat(chainRan).isTrue();

        chainRan.set(false);
        StepVerifier.create(run(post(), new TestingAuthenticationToken("someone", "secret"))).verifyComplete();
        assertThat(chainRan).isTrue();

        chainRan.set(false);
        StepVerifier.create(run(post(), tokenOf(null))).verifyComplete();
        assertThat(chainRan).isTrue();
        verify(markers, never()).isDeleted(anyString());
    }

    @Test
    void filter_shouldAnswerTheStoreError_whenTheMarkerCannotBeRead() {
        when(markers.isDeleted("lea")).thenReturn(Mono.error(new EventException(EventErrorCode.STORE_UNAVAILABLE)));
        MockServerWebExchange exchange = post();

        StepVerifier.create(run(exchange, tokenOf("lea"))).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(chainRan).isFalse();
    }

    @Test
    void filter_shouldNotCatchErrorsRaisedDownTheChain() {
        when(markers.isDeleted("lea")).thenReturn(Mono.just(false));
        EventException downstream = new EventException(EventErrorCode.EVENT_NOT_FOUND);
        WebFilterChain failing = exchange -> Mono.error(downstream);

        StepVerifier.create(filter.filter(post(), failing)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(tokenOf("lea"))))
                .expectErrorMatches(error -> error == downstream)
                .verify();
    }
}
