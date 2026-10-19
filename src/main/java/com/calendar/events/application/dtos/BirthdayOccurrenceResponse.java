package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Closeness;

import java.time.LocalDate;

/**
 * A birthday in the asked range: day and month of the given year, never the year of birth.
 *
 * @param closeness   null on the caller's own birthday
 * @param canOrganize true when the caller is a close friend, no surprise exists yet and the day is not past
 * @param surprise    null when there is none, and always on the caller's own birthday
 */
public record BirthdayOccurrenceResponse(
        String userId,
        String name,
        LocalDate date,
        boolean mine,
        Closeness closeness,
        boolean canOrganize,
        SurpriseResponse surprise
) {

    /** @param joined whether the caller takes part */
    public record SurpriseResponse(String eventId, String organizerId, boolean joined) {
    }
}
