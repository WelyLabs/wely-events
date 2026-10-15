package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErasureReportTest {

    private static final ErasureReport SOME = new ErasureReport(1, 2, 3, 4, 5, 6);

    @Test
    void none_shouldCountNothing() {
        assertThat(ErasureReport.NONE).isEqualTo(new ErasureReport(0, 0, 0, 0, 0, 0));
    }

    @Test
    void withBallotsWithdrawn_shouldReplaceOnlyThatCount() {
        assertThat(SOME.withBallotsWithdrawn(9)).isEqualTo(new ErasureReport(9, 2, 3, 4, 5, 6));
    }

    @Test
    void withReleased_shouldReplaceTheHandedOverAndDeletedCounts() {
        assertThat(SOME.withReleased(7, 8)).isEqualTo(new ErasureReport(1, 7, 8, 4, 5, 6));
    }

    @Test
    void withEventsLeft_shouldReplaceOnlyThatCount() {
        assertThat(SOME.withEventsLeft(9)).isEqualTo(new ErasureReport(1, 2, 3, 9, 5, 6));
    }

    @Test
    void withResponsesCleared_shouldReplaceOnlyThatCount() {
        assertThat(SOME.withResponsesCleared(9)).isEqualTo(new ErasureReport(1, 2, 3, 4, 9, 6));
    }

    @Test
    void withReceiptsDropped_shouldReplaceOnlyThatCount() {
        assertThat(SOME.withReceiptsDropped(9)).isEqualTo(new ErasureReport(1, 2, 3, 4, 5, 9));
    }
}
