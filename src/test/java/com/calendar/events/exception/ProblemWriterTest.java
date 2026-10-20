package com.calendar.events.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemWriterTest {

    private final ProblemWriter writer = new ProblemWriter(ServerCodecConfigurer.create());

    @Test
    void write_shouldAnswerTheProblemsStatusAsProblemJson() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/events-service/x"));

        StepVerifier.create(writer.write(exchange, Problems.of(BusinessErrorCode.ACCOUNT_DELETED, "/events-service/x", "r1")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"EVT-BUS-025\"")
                        .contains("\"requestId\":\"r1\"")
                        .contains("\"instance\":\"/events-service/x\""))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void write_shouldKeepTheHeadersAlreadySet() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");

        StepVerifier.create(writer.write(exchange, Problems.of(BusinessErrorCode.AUTHENTICATION_REQUIRED, "/x", "r2")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
