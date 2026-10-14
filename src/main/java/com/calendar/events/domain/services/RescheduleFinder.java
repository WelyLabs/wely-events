package com.calendar.events.domain.services;

import com.calendar.events.domain.models.TimeRange;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.LongStream;

/**
 * Proposes new times for an event, given when its people are busy.
 *
 * <p>Pure computation, no I/O. A proposal keeps the event's duration and moves it by whole steps
 * of {@link #STEP}, so it keeps its minutes: an event at 19:15 is offered 20:15, not 20:00. The
 * proposals closest to the original come first, later before earlier at equal distance, and two
 * proposals are at least {@link #SPACING} apart: three quarter-hour shifts would be one choice.
 */
final class RescheduleFinder {

    static final Duration STEP = Duration.ofMinutes(30);
    static final Duration SPACING = Duration.ofHours(1);
    /** The hours the calendar counts as free time; an event longer than them is not held to them. */
    static final LocalTime DAY_START = LocalTime.of(8, 0);
    static final LocalTime DAY_END = LocalTime.of(23, 0);

    private RescheduleFinder() {
    }

    /**
     * At most {@code limit} ranges of the event's duration, inside {@code window}, overlapping no
     * busy range and never on the original slot.
     */
    static List<TimeRange> proposals(TimeRange original, List<TimeRange> busy, TimeRange window, ZoneId zone, int limit) {
        Duration duration = original.duration();
        boolean heldToDayHours = duration.compareTo(Duration.between(DAY_START, DAY_END)) <= 0;
        long before = Duration.between(window.start(), original.start()).dividedBy(STEP) + 1;
        long after = Duration.between(original.start(), window.end()).dividedBy(STEP);

        List<TimeRange> picked = new ArrayList<>();
        LongStream.rangeClosed(-before, after)
                .filter(step -> step != 0)
                .boxed()
                .sorted(Comparator.<Long>comparingLong(Math::abs).thenComparing(step -> step < 0))
                .map(step -> shifted(original, step))
                .filter(candidate -> !candidate.start().isBefore(window.start()) && !candidate.end().isAfter(window.end()))
                .filter(candidate -> !heldToDayHours || withinDayHours(candidate, zone))
                .filter(candidate -> busy.stream().noneMatch(range -> overlaps(range, candidate)))
                .forEach(candidate -> {
                    if (picked.size() < limit && picked.stream().allMatch(other -> farEnough(other, candidate))) {
                        picked.add(candidate);
                    }
                });
        return picked;
    }

    private static TimeRange shifted(TimeRange original, long steps) {
        Duration shift = STEP.multipliedBy(steps);
        return new TimeRange(original.start().plus(shift), original.end().plus(shift));
    }

    /** Starts at or after DAY_START and ends by DAY_END on the same day, in the users' time zone. */
    private static boolean withinDayHours(TimeRange candidate, ZoneId zone) {
        ZonedDateTime start = candidate.start().atZone(zone);
        ZonedDateTime end = candidate.end().atZone(zone);
        return start.toLocalDate().equals(end.toLocalDate())
                && !start.toLocalTime().isBefore(DAY_START)
                && !end.toLocalTime().isAfter(DAY_END);
    }

    private static boolean overlaps(TimeRange a, TimeRange b) {
        return a.start().isBefore(b.end()) && a.end().isAfter(b.start());
    }

    private static boolean farEnough(TimeRange a, TimeRange b) {
        Instant first = a.start().isBefore(b.start()) ? a.start() : b.start();
        Instant second = a.start().isBefore(b.start()) ? b.start() : a.start();
        return Duration.between(first, second).compareTo(SPACING) >= 0;
    }
}
