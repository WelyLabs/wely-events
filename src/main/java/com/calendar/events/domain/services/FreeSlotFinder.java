package com.calendar.events.domain.services;

import com.calendar.events.domain.models.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the free parts of a window, given the busy ranges of everyone involved.
 *
 * <p>Pure computation, no I/O: the busy ranges are merged, the gaps between them inside the
 * window are kept, and each gap is shrunk to the grid (start rounded up, end rounded down) so
 * that a proposed slot starts on a quarter hour, like the calendar's own selection.
 */
final class FreeSlotFinder {

    private FreeSlotFinder() {
    }

    /** Free ranges of at least {@code minDuration}, in chronological order. */
    static List<TimeRange> freeRanges(TimeRange window, List<TimeRange> busy, Duration minDuration, Duration grid) {
        List<TimeRange> sorted = busy.stream()
                .filter(range -> range.end().isAfter(window.start()) && range.start().isBefore(window.end()))
                .sorted(Comparator.comparing(TimeRange::start))
                .toList();

        List<TimeRange> free = new ArrayList<>();
        Instant cursor = window.start();
        for (TimeRange range : sorted) {
            if (range.start().isAfter(cursor)) {
                addIfLongEnough(free, cursor, range.start(), minDuration, grid);
            }
            if (range.end().isAfter(cursor)) {
                cursor = range.end();
            }
        }
        if (window.end().isAfter(cursor)) {
            addIfLongEnough(free, cursor, window.end(), minDuration, grid);
        }
        return free;
    }

    private static void addIfLongEnough(List<TimeRange> free, Instant from, Instant to, Duration minDuration, Duration grid) {
        Instant start = ceil(from, grid);
        Instant end = floor(to, grid);
        if (end.isAfter(start) && Duration.between(start, end).compareTo(minDuration) >= 0) {
            free.add(new TimeRange(start, end));
        }
    }

    private static Instant floor(Instant instant, Duration grid) {
        long step = grid.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(instant.toEpochMilli(), step) * step);
    }

    private static Instant ceil(Instant instant, Duration grid) {
        Instant floor = floor(instant, grid);
        return floor.equals(instant) ? floor : floor.plus(grid);
    }
}
