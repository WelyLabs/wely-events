package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReunionCandidateTest {

    @Test
    void named_shouldKeepEverythingButTheName() {
        Instant since = Instant.parse("2025-01-10T10:00:00Z");
        Instant last = Instant.parse("2026-05-02T18:00:00Z");
        ReunionCandidate unnamed = new ReunionCandidate("hugo", null, Closeness.CLOSE, since, last, "e1", "Ciné", 5);

        assertThat(unnamed.named("Hugo"))
                .isEqualTo(new ReunionCandidate("hugo", "Hugo", Closeness.CLOSE, since, last, "e1", "Ciné", 5));
    }
}
