package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarLinkTest {

    private static final Instant EXPIRY = Instant.parse("2026-10-17T10:05:00Z");
    private final CalendarLink link = new CalendarLink("t", "e1", null, EXPIRY);

    @Test
    void isValidAt_shouldBeTrue_beforeItExpires() {
        assertThat(link.isValidAt(EXPIRY.minusSeconds(1))).isTrue();
    }

    @Test
    void isValidAt_shouldBeFalse_fromTheExpiryOn() {
        assertThat(link.isValidAt(EXPIRY)).isFalse();
        assertThat(link.isValidAt(EXPIRY.plusSeconds(1))).isFalse();
    }
}
