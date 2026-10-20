package com.calendar.events.configuration;

import com.calendar.events.exception.ProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemAccessDeniedHandlerTest {

    private final ProblemAccessDeniedHandler handler =
            new ProblemAccessDeniedHandler(new ProblemWriter(ServerCodecConfigurer.create()));

    @Test
    void handle_shouldAnswerOurForbiddenProblem_andKeepTheBearerHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.delete("/events-service/events/e1"));

        StepVerifier.create(handler.handle(exchange, new AccessDeniedException("denied")))
                .expectComplete()
                .verify(Duration.ofSeconds(10));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        StepVerifier.create(exchange.getResponse().getBodyAsString())
                .assertNext(body -> assertThat(body)
                        .contains("\"code\":\"EVT-REQ-002\"")
                        .contains("\"status\":403")
                        .contains("\"instance\":\"/events-service/events/e1\"")
                        .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\""))
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }
}
