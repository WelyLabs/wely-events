package com.calendar.events.configuration;

import com.calendar.events.exception.ProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemAuthenticationEntryPointTest {

    private final ProblemAuthenticationEntryPoint entryPoint =
            new ProblemAuthenticationEntryPoint(new ProblemWriter(ServerCodecConfigurer.create()));

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/events-service/events/me/feed"));
    }

    private static void assertBody(MockServerWebExchange exchange) {
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"EVT-REQ-001\"")
                        .contains("\"status\":401")
                        .contains("\"title\":\"Authentication required\"")
                        .contains("\"instance\":\"/events-service/events/me/feed\"")
                        .contains("\"type\":\"https://welylabs.app/problems/evt-req-001\"")
                        .contains("\"timestamp\":")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\""))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void commence_shouldAnswerOurProblem_andKeepTheBearerChallenge() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(entryPoint.commence(exchange, new BadCredentialsException("no token")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertBody(exchange);
    }

    @Test
    void commence_shouldKeepTheResourceServersErrorDetails_forAnInvalidToken() {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(entryPoint.commence(exchange, new InvalidBearerTokenException("expired")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
                .startsWith("Bearer").contains("error=\"invalid_token\"");
        assertBody(exchange);
    }
}
