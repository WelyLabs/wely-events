package com.calendar.events.domain.services;

import com.calendar.events.domain.models.TimeRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RescheduleFinderTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    /** Thursday 16 October 2026, 14:00 – 16:00 in Paris (UTC+2). */
    private static final TimeRange BOWLING = range("2026-10-16T12:00:00Z", "2026-10-16T14:00:00Z");
    private static final TimeRange WEEK = range("2026-10-15T12:00:00Z", "2026-10-23T14:00:00Z");

    private static TimeRange range(String start, String end) {
        return new TimeRange(Instant.parse(start), Instant.parse(end));
    }

    @Test
    void proposals_shouldMoveByWholeSteps_closestFirst_laterBeforeEarlier_anHourApart() {
        List<TimeRange> proposals = RescheduleFinder.proposals(BOWLING, List.of(), WEEK, PARIS, 3);

        // +30 min, then -30 min (an hour from it), then +1 h 30 (+1 h is too close to +30 min).
        assertThat(proposals).containsExactly(
                range("2026-10-16T12:30:00Z", "2026-10-16T14:30:00Z"),
                range("2026-10-16T11:30:00Z", "2026-10-16T13:30:00Z"),
                range("2026-10-16T13:30:00Z", "2026-10-16T15:30:00Z"));
    }

    @Test
    void proposals_shouldShiftAnHourLater_whenAMedicalAppointmentTakesTheFirstHour() {
        // The use case: an appointment 14:00 – 15:00 (Paris) under a bowling 14:00 – 16:00.
        TimeRange appointment = range("2026-10-16T12:00:00Z", "2026-10-16T13:00:00Z");

        List<TimeRange> proposals = RescheduleFinder.proposals(BOWLING, List.of(appointment), WEEK, PARIS, 3);

        assertThat(proposals.getFirst()).isEqualTo(range("2026-10-16T13:00:00Z", "2026-10-16T15:00:00Z"));
        assertThat(proposals).noneMatch(proposal -> proposal.start().isBefore(appointment.end())
                && proposal.end().isAfter(appointment.start()));
    }

    @Test
    void proposals_shouldKeepTheMinutes_andNeverOfferTheOriginalSlot() {
        TimeRange quarterPast = range("2026-10-16T12:15:00Z", "2026-10-16T13:15:00Z");

        List<TimeRange> proposals = RescheduleFinder.proposals(quarterPast, List.of(), WEEK, PARIS, 3);

        assertThat(proposals).isNotEmpty().doesNotContain(quarterPast)
                .allMatch(proposal -> proposal.start().toString().contains(":15:") || proposal.start().toString().contains(":45:"));
    }

    @Test
    void proposals_shouldStayWithinDayHours_inTheUsersTimeZone() {
        // 21:00 – 23:00 in Paris: later would end past 23:00, earlier is free.
        TimeRange evening = range("2026-10-16T19:00:00Z", "2026-10-16T21:00:00Z");

        List<TimeRange> proposals = RescheduleFinder.proposals(evening, List.of(), WEEK, PARIS, 3);

        assertThat(proposals).containsExactly(
                range("2026-10-16T18:30:00Z", "2026-10-16T20:30:00Z"),
                range("2026-10-16T17:30:00Z", "2026-10-16T19:30:00Z"),
                range("2026-10-16T16:30:00Z", "2026-10-16T18:30:00Z"));
    }

    @Test
    void proposals_shouldNotHoldAnEventLongerThanTheDayHoursToThem() {
        TimeRange weekend = range("2026-10-17T08:00:00Z", "2026-10-18T20:00:00Z");

        List<TimeRange> proposals = RescheduleFinder.proposals(weekend, List.of(), WEEK, PARIS, 1);

        assertThat(proposals).containsExactly(range("2026-10-17T08:30:00Z", "2026-10-18T20:30:00Z"));
    }

    @Test
    void proposals_shouldStayInsideTheWindow() {
        TimeRange window = range("2026-10-16T12:00:00Z", "2026-10-16T15:00:00Z");

        List<TimeRange> proposals = RescheduleFinder.proposals(BOWLING, List.of(), window, PARIS, 3);

        assertThat(proposals).containsExactly(range("2026-10-16T12:30:00Z", "2026-10-16T14:30:00Z"));
    }

    @Test
    void proposals_shouldGoToAnotherDay_whenTheDayIsFull() {
        TimeRange wholeThursday = range("2026-10-16T06:00:00Z", "2026-10-16T21:00:00Z");
        TimeRange wholeWednesday = range("2026-10-15T06:00:00Z", "2026-10-15T21:00:00Z");

        List<TimeRange> proposals = RescheduleFinder.proposals(BOWLING, List.of(wholeThursday, wholeWednesday), WEEK, PARIS, 1);

        assertThat(proposals).containsExactly(range("2026-10-17T06:00:00Z", "2026-10-17T08:00:00Z"));
    }

    @Test
    void proposals_shouldBeEmpty_whenEveryoneIsBusyAllWeek() {
        List<TimeRange> proposals = RescheduleFinder.proposals(BOWLING, List.of(WEEK), WEEK, PARIS, 3);

        assertThat(proposals).isEmpty();
    }
}
