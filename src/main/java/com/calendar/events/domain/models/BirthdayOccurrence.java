package com.calendar.events.domain.models;

import java.time.LocalDate;

/**
 * A birthday falling in the asked range, as the viewer sees it: day and month only, never the year
 * of birth.
 *
 * @param closeness   how close the viewer is to this friend; null on the viewer's own birthday
 * @param canOrganize whether the viewer may create a surprise for it: a close friend, none yet
 * @param surprise    the surprise organized for it; always null on the viewer's own birthday
 */
public record BirthdayOccurrence(
        String userId,
        String name,
        LocalDate date,
        boolean mine,
        Closeness closeness,
        boolean canOrganize,
        Surprise surprise
) {

    /** @param joined whether the viewer takes part in it */
    public record Surprise(String eventId, String organizerId, boolean joined) {
    }
}
