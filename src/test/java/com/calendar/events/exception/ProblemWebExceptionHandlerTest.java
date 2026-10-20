package com.calendar.events.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.WebExceptionHandler;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemWebExceptionHandlerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ProblemWebExceptionHandler handler = new ProblemWebExceptionHandler(
            new GlobalErrorHandler(new TechnicalErrorCounter(meterRegistry)), new ProblemWriter(ServerCodecConfigurer.create()));

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/events-service/events"));
    }

    private static String body(MockServerWebExchange exchange) {
        return exchange.getResponse().getBodyAsString().block(TIMEOUT);
    }

    private double technicalFailures(String code) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_HTTP).count();
    }

    private void handle(MockServerWebExchange exchange, Throwable failure) {
        StepVerifier.create(handler.handle(exchange, failure)).expectComplete().verify(TIMEOUT);
    }

    @Test
    void handle_shouldAnswerABusinessFailureWithItsCode() {
        MockServerWebExchange exchange = exchange();

        handle(exchange, new BusinessException(BusinessErrorCode.ACCOUNT_DELETED));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(body(exchange)).contains("\"code\":\"EVT-BUS-025\"")
                .contains("\"requestId\":\"" + exchange.getRequest().getId() + "\"");
        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    void handle_shouldAnswerAndCountATechnicalFailure() {
        MockServerWebExchange exchange = exchange();

        handle(exchange, new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(body(exchange)).contains("\"code\":\"EVT-TEC-005\"");
        assertThat(technicalFailures("EVT-TEC-005")).isEqualTo(1.0);
    }

    @Test
    void handle_shouldKeepAFrameworkStatus_underTheRequestCode() {
        MockServerWebExchange exchange = exchange();

        handle(exchange, new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(body(exchange)).contains("\"code\":\"" + GlobalErrorHandler.REQUEST_REFUSED_CODE + "\"");
    }

    @Test
    void handle_shouldAnswerTooLarge_forAnUploadPastTheCodecLimit() {
        MockServerWebExchange exchange = exchange();

        handle(exchange, new org.springframework.core.io.buffer.DataBufferLimitException("too big"));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(body(exchange)).contains("\"code\":\"EVT-BUS-012\"");
    }

    @Test
    void handle_shouldAnswerAndCountAnythingElse_asUnexpected() {
        MockServerWebExchange exchange = exchange();

        handle(exchange, new IllegalStateException("mongodb://admin:hunter2@host"));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body(exchange)).contains("\"code\":\"" + GlobalErrorHandler.UNEXPECTED_FAILURE_CODE + "\"")
                .doesNotContain("hunter2");
        assertThat(technicalFailures(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    void handle_shouldPassTheFailureOn_whenTheResponseIsAlreadyCommitted() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block(TIMEOUT);
        TechnicalException failure = new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE);

        StepVerifier.create(handler.handle(exchange, failure))
                .expectErrorMatches(error -> error == failure)
                .verify(TIMEOUT);
        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    void handle_shouldPassAJvmErrorOn() {
        StackOverflowError failure = new StackOverflowError();

        StepVerifier.create(handler.handle(exchange(), failure))
                .expectErrorMatches(error -> error == failure)
                .verify(TIMEOUT);
    }

    /** The real application, with a filter that fails before any controller is chosen. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    @Import(InTheApplication.FailingFilter.class)
    class InTheApplication {

        static final String FAILING_PATH = "/test-only/failing-filter";

        @TestConfiguration
        static class FailingFilter {

            /** Ahead of the security chain, so it fails for an anonymous request too. */
            @Bean
            @Order(Ordered.HIGHEST_PRECEDENCE)
            WebFilter failingFilter() {
                return (exchange, chain) -> exchange.getRequest().getPath().value().equals(FAILING_PATH)
                        ? Mono.error(new TechnicalException(TechnicalErrorCode.SOCIAL_UNAVAILABLE))
                        : chain.filter(exchange);
            }
        }

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("a failure in a web filter answers our problem, not Boot's error JSON")
        void handle_shouldAnswerAFilterFailureWithOurProblem() {
            WebTestClient.bindToApplicationContext(context).configureClient()
                    .responseTimeout(Duration.ofSeconds(30)).build()
                    .get().uri(FAILING_PATH)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(TechnicalErrorCode.SOCIAL_UNAVAILABLE.getCode())
                    .jsonPath("$.instance").isEqualTo(FAILING_PATH)
                    .jsonPath("$.requestId").isNotEmpty();
        }

        @Test
        @DisplayName("it runs before Boot's own error handler")
        void handle_shouldComeFirstAmongWebExceptionHandlers() {
            assertThat(context.getBeanProvider(WebExceptionHandler.class).orderedStream().findFirst())
                    .containsInstanceOf(ProblemWebExceptionHandler.class);
        }
    }
}
