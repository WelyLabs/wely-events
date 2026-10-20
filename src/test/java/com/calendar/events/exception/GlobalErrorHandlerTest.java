package com.calendar.events.exception;

import com.calendar.events.domain.ports.DeletedUserMarkers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

class GlobalErrorHandlerTest {

    private WebTestClient client;
    private SimpleMeterRegistry meterRegistry;

    @RestController
    static class TestController {

        @GetMapping("/not-found")
        Mono<Void> notFound() {
            return Mono.error(new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND));
        }

        @GetMapping("/forbidden")
        Mono<Void> forbidden() {
            return Mono.error(new BusinessException(BusinessErrorCode.EVENT_ACCESS_DENIED));
        }

        @GetMapping("/generic-error")
        Mono<Void> genericError() {
            return Mono.error(new IllegalStateException("mongodb://admin:hunter2@host/events"));
        }

        @GetMapping("/too-large")
        Mono<Void> tooLarge() {
            return Mono.error(new BusinessException(BusinessErrorCode.MEDIA_TOO_LARGE));
        }

        @GetMapping("/over-codec-limit")
        Mono<Void> overCodecLimit() {
            return Mono.error(new DataBufferLimitException("Part exceeded the limit"));
        }

        @GetMapping("/account-deleted")
        Mono<Void> accountDeleted() {
            return Mono.error(new BusinessException(BusinessErrorCode.ACCOUNT_DELETED));
        }

        @GetMapping("/media-down")
        Mono<Void> mediaDown() {
            return Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE));
        }

        @GetMapping("/erasure-incomplete")
        Mono<Void> erasureIncomplete() {
            return Mono.error(new TechnicalException(TechnicalErrorCode.ERASURE_INCOMPLETE));
        }

        @GetMapping("/gone")
        Mono<Void> gone() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        @GetMapping("/surprise-exists")
        Mono<Void> surpriseExists() {
            return Mono.error(new BusinessException(BusinessErrorCode.SURPRISE_EXISTS, java.util.Map.of("eventId", "s1")));
        }

        @GetMapping("/invalid")
        Mono<Void> invalid() throws NoSuchMethodException {
            BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "request");
            result.addError(new FieldError("request", "title", "must not be blank"));
            return Mono.error(new WebExchangeBindException(
                    new MethodParameter(TestController.class.getDeclaredMethod("invalid"), -1), result));
        }

        @GetMapping("/not-allowed")
        Mono<Void> notAllowed() {
            return Mono.error(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));
        }
    }

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler(new TechnicalErrorCounter(meterRegistry)))
                .build();
    }

    @Test
    @DisplayName("a business failure answers as application/problem+json")
    void handleBusinessException_shouldAnswerAsProblemDetail() {
        client.get().uri("/not-found")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Event not found")
                .jsonPath("$.code").isEqualTo("EVT-BUS-001")
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/evt-bus-001")
                .jsonPath("$.instance").isEqualTo("/not-found")
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    private static void assertNotBlank(Object requestId) {
        assertThat(requestId).isInstanceOf(String.class);
        assertThat((String) requestId).isNotBlank();
    }

    private double technicalFailures(String code) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_HTTP).count();
    }

    @Test
    @DisplayName("a business failure is not counted as a technical one")
    void handleBusinessException_shouldNotCountATechnicalFailure() {
        client.get().uri("/not-found").exchange().expectStatus().isNotFound();

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    @DisplayName("a refused access answers 403, under its own code")
    void handleBusinessException_shouldAnswerForbiddenForANonOrganizer() {
        client.get().uri("/forbidden")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-002")
                .jsonPath("$.title").isEqualTo("Not the organizer");
    }

    @Test
    @DisplayName("a deleted account answers 410, under its own code")
    void handleBusinessException_shouldAnswerGoneForADeletedAccount() {
        client.get().uri("/account-deleted")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.GONE)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-025")
                .jsonPath("$.title").isEqualTo("Account deleted");
    }

    @Test
    @DisplayName("an unexpected error leaks nothing from the original message")
    void handleUnexpectedException_shouldNotLeakTheCause() {
        client.get().uri("/generic-error")
                .exchange()
                .expectStatus().is5xxServerError()
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank)
                .jsonPath("$.detail").value(detail -> {
                    // The message carried a Mongo URI with a password.
                    if (detail.toString().contains("hunter2") || detail.toString().contains("mongodb")) {
                        throw new AssertionError("the original message leaked into the response");
                    }
                });
    }

    @Test
    @DisplayName("an unknown path keeps its 404 instead of becoming a 500")
    void handleResponseStatusException_shouldKeepTheOriginalStatus() {
        // The regression this guards: @ExceptionHandler(Exception.class) also catches
        // ResponseStatusException, so before EVT-REQ-000 existed every unknown path answered
        // 500. The service reported a fault of its own for a request it had handled correctly.
        client.get().uri("/gone")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    @DisplayName("a rejected method keeps its 405")
    void handleResponseStatusException_shouldCarryAnyStatusThrough() {
        client.get().uri("/not-allowed")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectBody()
                .jsonPath("$.status").isEqualTo(405)
                .jsonPath("$.title").isEqualTo("Method Not Allowed")
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.REQUEST_REFUSED_CODE)
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }


    @Test
    @DisplayName("a picture too large answers 413")
    void handleBusinessException_shouldAnswerContentTooLargeForAPictureTooLarge() {
        client.get().uri("/too-large")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-012");
    }

    @Test
    @DisplayName("an upload past the codec's own limit answers 413 too, not 500")
    void handleDataBufferLimitException_shouldAnswerContentTooLarge() {
        client.get().uri("/over-codec-limit")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONTENT_TOO_LARGE)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-012");
    }

    @Test
    @DisplayName("wely-media down answers 502 as application/problem+json, under its technical code")
    void handleTechnicalException_shouldAnswerBadGatewayWhenMediaIsDown() {
        client.get().uri("/media-down")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.code").isEqualTo("EVT-TEC-003")
                .jsonPath("$.title").isEqualTo("Media service unavailable")
                .jsonPath("$.detail").isEqualTo("The picture could not be stored; try again shortly.")
                .jsonPath("$.type").isEqualTo("https://welylabs.app/problems/evt-tec-003")
                .jsonPath("$.instance").isEqualTo("/media-down")
                .jsonPath("$.timestamp").exists()
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
    }

    @Test
    @DisplayName("a technical failure is counted under its own code")
    void handleTechnicalException_shouldCountTheFailureByCode() {
        client.get().uri("/media-down").exchange().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
        client.get().uri("/media-down").exchange().expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY);
        client.get().uri("/erasure-incomplete").exchange().expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(technicalFailures(TechnicalErrorCode.MEDIA_UNAVAILABLE.getCode())).isEqualTo(2.0);
        assertThat(technicalFailures(TechnicalErrorCode.ERASURE_INCOMPLETE.getCode())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an unexpected failure is counted as a technical one, under the catch-all code")
    void handleUnexpectedException_shouldCountTheFailure() {
        client.get().uri("/generic-error").exchange().expectStatus().is5xxServerError();

        assertThat(technicalFailures(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a validation failure lists the invalid fields, under its own code")
    void handleValidationException_shouldListTheInvalidFields() {
        client.get().uri("/invalid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(GlobalErrorHandler.VALIDATION_FAILED_CODE)
                .jsonPath("$.errors.title").isEqualTo("must not be blank")
                .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    @DisplayName("an erasure that kept missing answers 503, the one technical failure that is not a 502")
    void handleTechnicalException_shouldKeepTheCodesOwnStatus() {
        client.get().uri("/erasure-incomplete")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-TEC-006")
                .jsonPath("$.title").isEqualTo("Account erasure incomplete");
    }

    @Test
    @DisplayName("a refusal's extra properties go into the problem, as a surprise's existing id does")
    void handleBusinessException_shouldCarryTheExceptionsProperties() {
        client.get().uri("/surprise-exists")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-030")
                .jsonPath("$.eventId").isEqualTo("s1");
    }
    /**
     * The real application context, where Boot's own exception handlers sit next to this advice. Binding
     * the advice alone, as above, cannot show which one answers: these requests go through the whole app.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("test")
    class InTheApplication {

        @Autowired
        private ApplicationContext context;

        @MockitoBean
        private ReactiveJwtDecoder jwtDecoder;

        /** Every write asks the register first; a live account lets it through to the controller. */
        @MockitoBean
        private DeletedUserMarkers markers;

        private WebTestClient client() {
            return WebTestClient.bindToApplicationContext(context)
                    .apply(springSecurity())
                    .configureClient()
                    .responseTimeout(Duration.ofSeconds(30))
                    .build()
                    .mutateWith(mockJwt().jwt(jwt -> jwt.claim("businessId", "lea")));
        }

        private void expectOurProblem(WebTestClient.ResponseSpec response, int status, String code) {
            response.expectStatus().isEqualTo(status)
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.status").isEqualTo(status)
                    .jsonPath("$.code").isEqualTo(code)
                    .jsonPath("$.requestId").value(GlobalErrorHandlerTest::assertNotBlank);
        }

        @Test
        @DisplayName("the gateway's request id is the problem's, and comes back on the response")
        void handleResponseStatusException_shouldUseTheGatewaysRequestId() {
            String gatewayId = "abcd1234-5678-90ef-gateway";

            client().get().uri("/events-service/no-such-path")
                    .header(RequestIds.REQUEST_ID_HEADER, gatewayId)
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectHeader().valueEquals(RequestIds.REQUEST_ID_HEADER, gatewayId)
                    .expectBody()
                    .jsonPath("$.requestId").isEqualTo(gatewayId);
        }

        @Test
        @DisplayName("an unknown path answers our 404 problem")
        void handleResponseStatusException_shouldAnswerAnUnknownPath() {
            expectOurProblem(client().get().uri("/events-service/no-such-path").exchange(),
                    404, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        @DisplayName("a method the path does not take answers our 405 problem")
        void handleResponseStatusException_shouldAnswerAWrongMethod() {
            when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

            expectOurProblem(client().patch().uri("/events-service/events/me/feed").exchange(),
                    405, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        @DisplayName("a body in a type the endpoint does not read answers our 415 problem")
        void handleResponseStatusException_shouldAnswerAnUnsupportedContentType() {
            when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

            expectOurProblem(client().post().uri("/events-service/events")
                            .contentType(MediaType.TEXT_PLAIN).bodyValue("a title").exchange(),
                    415, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        @DisplayName("a body that is not JSON answers our 400 problem")
        void handleResponseStatusException_shouldAnswerAnUnreadableBody() {
            when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

            expectOurProblem(client().post().uri("/events-service/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            // Bytes, not a String: a String would be encoded as a JSON string.
                            .bodyValue("{not json".getBytes(java.nio.charset.StandardCharsets.UTF_8)).exchange(),
                    400, GlobalErrorHandler.REQUEST_REFUSED_CODE);
        }

        @Test
        @DisplayName("an invalid field answers our 400 validation problem, with the field listed")
        void handleValidationException_shouldAnswerAnInvalidField() {
            when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

            expectOurProblem(client().post().uri("/events-service/events")
                            .contentType(MediaType.APPLICATION_JSON).bodyValue(java.util.Map.of("title", " ", "allDay", false)).exchange(),
                    400, GlobalErrorHandler.VALIDATION_FAILED_CODE);
        }

        /** A required number or flag left out answers our validation problem naming it, not an unreadable body. */
        private void expectRequired(String uri, Object body, String field) {
            when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

            client().post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(GlobalErrorHandler.VALIDATION_FAILED_CODE)
                    .jsonPath("$.errors['" + field + "']").exists();
        }

        @Test
        @DisplayName("an event without allDay names the field")
        void handleValidationException_shouldNameAMissingAllDay() {
            expectRequired("/events-service/events", java.util.Map.of("title", "Quiz",
                    "startDate", "2099-01-01T18:00:00Z", "location", "Lyon"), "allDay");
        }

        @Test
        @DisplayName("an expense or a settlement without amountCents names the field")
        void handleValidationException_shouldNameAMissingAmount() {
            expectRequired("/events-service/events/e1/expenses", java.util.Map.of("title", "Courses",
                    "paidBy", "lea", "split", "EQUAL", "shares", java.util.List.of(java.util.Map.of("userId", "lea", "value", 1))),
                    "amountCents");
            expectRequired("/events-service/events/e1/expenses/settlements",
                    java.util.Map.of("fromUserId", "lea", "toUserId", "tom"), "amountCents");
        }

        @Test
        @DisplayName("a share without value names the field")
        void handleValidationException_shouldNameAMissingShareValue() {
            expectRequired("/events-service/events/e1/expenses", java.util.Map.of("title", "Courses", "amountCents", 3000,
                    "paidBy", "lea", "split", "SHARES", "shares", java.util.List.of(java.util.Map.of("userId", "lea"))),
                    "shares[0].value");
        }

        @Test
        @DisplayName("a free-slot search without durationMinutes names the field")
        void handleValidationException_shouldNameAMissingDuration() {
            expectRequired("/events-service/events/me/free-slots", java.util.Map.of("friendIds", java.util.List.of("tom"),
                    "start", "2099-01-01T00:00:00Z", "end", "2099-01-02T00:00:00Z"), "durationMinutes");
        }
    }
}
