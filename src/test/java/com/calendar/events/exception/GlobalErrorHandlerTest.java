package com.calendar.events.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

class GlobalErrorHandlerTest {

    private WebTestClient client;

    @RestController
    static class TestController {

        @GetMapping("/not-found")
        Mono<Void> notFound() {
            return Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND));
        }

        @GetMapping("/forbidden")
        Mono<Void> forbidden() {
            return Mono.error(new EventException(EventErrorCode.EVENT_ACCESS_DENIED));
        }

        @GetMapping("/generic-error")
        Mono<Void> genericError() {
            return Mono.error(new IllegalStateException("mongodb://admin:hunter2@host/events"));
        }

        @GetMapping("/too-large")
        Mono<Void> tooLarge() {
            return Mono.error(new EventException(EventErrorCode.MEDIA_TOO_LARGE));
        }

        @GetMapping("/over-codec-limit")
        Mono<Void> overCodecLimit() {
            return Mono.error(new DataBufferLimitException("Part exceeded the limit"));
        }

        @GetMapping("/account-deleted")
        Mono<Void> accountDeleted() {
            return Mono.error(new EventException(EventErrorCode.ACCOUNT_DELETED));
        }

        @GetMapping("/media-down")
        Mono<Void> mediaDown() {
            return Mono.error(new EventException(EventErrorCode.MEDIA_UNAVAILABLE));
        }

        @GetMapping("/gone")
        Mono<Void> gone() {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }

        @GetMapping("/surprise-exists")
        Mono<Void> surpriseExists() {
            return Mono.error(new EventException(EventErrorCode.SURPRISE_EXISTS, java.util.Map.of("eventId", "s1")));
        }

        @GetMapping("/not-allowed")
        Mono<Void> notAllowed() {
            return Mono.error(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED));
        }
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToController(new TestController())
                .controllerAdvice(new GlobalErrorHandler())
                .build();
    }

    @Test
    @DisplayName("a business failure answers as application/problem+json")
    void handleEventException_shouldAnswerAsProblemDetail() {
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
                .jsonPath("$.timestamp").exists();
    }

    @Test
    @DisplayName("a refused access answers 403, under its own code")
    void handleEventException_shouldAnswerForbiddenForANonOrganizer() {
        client.get().uri("/forbidden")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-002")
                .jsonPath("$.title").isEqualTo("Not the organizer");
    }

    @Test
    @DisplayName("a deleted account answers 410, under its own code")
    void handleEventException_shouldAnswerGoneForADeletedAccount() {
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
                .jsonPath("$.code").isEqualTo("EVT-TEC-000")
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
                .jsonPath("$.code").isEqualTo("EVT-REQ-000");
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
                .jsonPath("$.code").isEqualTo("EVT-REQ-000");
    }


    @Test
    @DisplayName("a picture too large answers 413")
    void handleEventException_shouldAnswerContentTooLargeForAPictureTooLarge() {
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
    @DisplayName("wely-media down answers 502")
    void handleEventException_shouldAnswerBadGatewayWhenMediaIsDown() {
        client.get().uri("/media-down")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-TEC-003");
    }

    @Test
    @DisplayName("a refusal's extra properties go into the problem, as a surprise's existing id does")
    void handleEventException_shouldCarryTheExceptionsProperties() {
        client.get().uri("/surprise-exists")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EVT-BUS-030")
                .jsonPath("$.eventId").isEqualTo("s1");
    }
}
