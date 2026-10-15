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
        ProblemDetail problem = Problems.of(EventErrorCode.ACCOUNT_DELETED, "/events");

        assertThat(problem.getStatus()).isEqualTo(410);
        assertThat(problem.getTitle()).isEqualTo(EventErrorCode.ACCOUNT_DELETED.getTitle());
        assertThat(problem.getDetail()).isEqualTo(EventErrorCode.ACCOUNT_DELETED.getDetail());
        assertThat(problem.getProperties()).containsEntry("code", "EVT-BUS-025");
    }

    @Test
    void of_shouldStampATimestamp() {
        Instant before = Instant.now();

        ProblemDetail problem = Problems.of(EventErrorCode.EVENT_NOT_FOUND, "/events/1");

        assertThat(Instant.parse((String) problem.getProperties().get("timestamp"))).isAfterOrEqualTo(before);
    }
}
