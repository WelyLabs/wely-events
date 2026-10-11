package com.calendar.events.domain.services;

import com.calendar.events.domain.models.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FreeSlotFinderTest {

    private static final Duration GRID = Duration.ofMinutes(15);
    private static final Duration HOUR = Duration.ofHours(1);

    private static Instant at(String time) {
        return Instant.parse("2026-10-17T" + time + ":00Z");
    }

    private static TimeRange range(String from, String to) {
        return new TimeRange(at(from), at(to));
    }

    private static final TimeRange WINDOW = range("10:00", "18:00");

    @Test
    void freeRanges_shouldReturnTheWholeWindow_whenNobodyIsBusy() {
        assertThat(FreeSlotFinder.freeRanges(WINDOW, List.of(), HOUR, GRID)).containsExactly(WINDOW);
    }

    @Test
    void freeRanges_shouldReturnTheGapsAroundBusyRanges() {
        List<TimeRange> busy = List.of(range("12:00", "13:00"), range("15:00", "16:30"));

        assertThat(FreeSlotFinder.freeRanges(WINDOW, busy, HOUR, GRID)).containsExactly(
                range("10:00", "12:00"), range("13:00", "15:00"), range("16:30", "18:00"));
    }

    @Test
    void freeRanges_shouldMergeOverlappingAndNestedBusyRanges_inAnyOrder() {
        // Two people booked at overlapping times, given out of order, plus one nested inside.
        List<TimeRange> busy = List.of(range("13:30", "15:00"), range("12:00", "14:00"), range("12:30", "13:00"));

        assertThat(FreeSlotFinder.freeRanges(WINDOW, busy, HOUR, GRID)).containsExactly(
                range("10:00", "12:00"), range("15:00", "18:00"));
    }

    @Test
    void freeRanges_shouldSkipGapsShorterThanTheDuration() {
        List<TimeRange> busy = List.of(range("11:00", "12:00"), range("12:45", "18:00"));

        assertThat(FreeSlotFinder.freeRanges(WINDOW, busy, HOUR, GRID)).containsExactly(range("10:00", "11:00"));
    }

    @Test
    void freeRanges_shouldBeEmpty_whenTheWindowIsFullyBooked() {
        assertThat(FreeSlotFinder.freeRanges(WINDOW, List.of(range("09:00", "19:00")), HOUR, GRID)).isEmpty();
    }

    @Test
    void freeRanges_shouldIgnoreBusyRangesOutsideTheWindow_andClipThoseAcrossItsEdges() {
        List<TimeRange> busy = List.of(range("07:00", "08:00"), range("09:00", "10:30"), range("17:30", "20:00"));

        assertThat(FreeSlotFinder.freeRanges(WINDOW, busy, HOUR, GRID)).containsExactly(range("10:30", "17:30"));
    }

    @Test
    void freeRanges_shouldAlignToTheGrid_roundingTheStartUpAndTheEndDown() {
        // A slot starting at 12:07 would be odd to propose: it becomes 12:15.
        List<TimeRange> busy = List.of(range("10:00", "12:07"), range("16:52", "18:00"));

        assertThat(FreeSlotFinder.freeRanges(WINDOW, busy, HOUR, GRID)).containsExactly(range("12:15", "16:45"));
    }

    @Test
    void freeRanges_shouldDropAGapThatNoLongerFitsOnceAligned() {
        // 61 minutes free, but 10:07 to 11:08 only holds 10:15 to 11:00 on the grid.
        List<TimeRange> busy = List.of(range("10:00", "10:07"), range("11:08", "18:00"));

        assertThat(FreeSlotFinder.freeRanges(WINDOW, busy, HOUR, GRID)).isEmpty();
    }

    @Test
    void freeRanges_shouldSpanSeveralDays_whenTheWindowDoes() {
        // The window is continuous, nights included.
        TimeRange weekend = new TimeRange(Instant.parse("2026-10-16T14:00:00Z"), Instant.parse("2026-10-18T20:00:00Z"));
        List<TimeRange> busy = List.of(new TimeRange(Instant.parse("2026-10-17T09:00:00Z"), Instant.parse("2026-10-17T23:00:00Z")));

        assertThat(FreeSlotFinder.freeRanges(weekend, busy, HOUR, GRID)).containsExactly(
                new TimeRange(Instant.parse("2026-10-16T14:00:00Z"), Instant.parse("2026-10-17T09:00:00Z")),
                new TimeRange(Instant.parse("2026-10-17T23:00:00Z"), Instant.parse("2026-10-18T20:00:00Z")));
    }
}
