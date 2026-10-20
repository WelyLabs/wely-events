package com.calendar.events.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemsTest {

    @Test
    void of_shouldCarryTheStatusCodeTypeTitleAndInstance() {
        ProblemDetail problem = Problems.of(HttpStatus.CONFLICT, "EVT-BUS-099", "A title", "A detail", "/events/1");

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("A title");
        assertThat(problem.getDetail()).isEqualTo("A detail");
        assertThat(problem.getType()).isEqualTo(URI.create("https://welylabs.app/problems/evt-bus-099"));
        assertThat(problem.getInstance()).isEqualTo(URI.create("/events/1"));
        assertThat(problem.getProperties()).containsEntry("code", "EVT-BUS-099");
    }

    @Test
    void of_shouldBuildFromAnErrorCode() {
        ProblemDetail problem = Problems.of(BusinessErrorCode.ACCOUNT_DELETED, "/events");

        assertThat(problem.getStatus()).isEqualTo(410);
        assertThat(problem.getTitle()).isEqualTo(BusinessErrorCode.ACCOUNT_DELETED.getTitle());
        assertThat(problem.getDetail()).isEqualTo(BusinessErrorCode.ACCOUNT_DELETED.getDetail());
        assertThat(problem.getProperties()).containsEntry("code", "EVT-BUS-025");
    }

    @Test
    void of_shouldBuildFromATechnicalErrorCode() {
        ProblemDetail problem = Problems.of(TechnicalErrorCode.STORE_UNAVAILABLE, "/events", "req-1");

        assertThat(problem.getStatus()).isEqualTo(502);
        assertThat(problem.getTitle()).isEqualTo(TechnicalErrorCode.STORE_UNAVAILABLE.getTitle());
        assertThat(problem.getDetail()).isEqualTo(TechnicalErrorCode.STORE_UNAVAILABLE.getDetail());
        assertThat(problem.getType()).isEqualTo(URI.create("https://welylabs.app/problems/evt-tec-005"));
        assertThat(problem.getProperties()).containsEntry("code", "EVT-TEC-005").containsEntry("requestId", "req-1");
    }

    @Test
    void of_shouldCarryTheRequestId_whenGivenOne() {
        ProblemDetail problem = Problems.of(HttpStatus.BAD_REQUEST, GlobalErrorHandler.VALIDATION_CODE, "A title", "A detail", "/events", "req-2");

        assertThat(problem.getProperties()).containsEntry(Problems.REQUEST_ID, "req-2").containsEntry("code", GlobalErrorHandler.VALIDATION_CODE);
    }

    @Test
    void of_shouldCarryTheRequestId_forABusinessCode() {
        ProblemDetail problem = Problems.of(BusinessErrorCode.ACCOUNT_DELETED, "/events", "req-3");

        assertThat(problem.getStatus()).isEqualTo(410);
        assertThat(problem.getProperties()).containsEntry(Problems.REQUEST_ID, "req-3");
    }

    @Test
    void of_shouldStampATimestamp() {
        Instant before = Instant.now();

        ProblemDetail problem = Problems.of(BusinessErrorCode.EVENT_NOT_FOUND, "/events/1");

        assertThat(Instant.parse((String) problem.getProperties().get("timestamp"))).isAfterOrEqualTo(before);
    }
}
