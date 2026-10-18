package com.calendar.events.domain.models;

import java.time.Instant;

/**
 * A close or intimate friend the user has not been out with for a while, and nothing planned together.
 *
 * @param lastSharedAt the start of their last shared outing; null when they never had one
 * @param monthsApart  whole months since then, in the user's time zone; null when they never had one
 */
public record ReunionCandidate(String friendId, String name, Closeness closeness, Instant since,
                               Instant lastSharedAt, String lastSharedEventId, String lastSharedTitle,
                               Integer monthsApart) {

    /** The same candidate, named. */
    public ReunionCandidate named(String friendName) {
        return new ReunionCandidate(friendId, friendName, closeness, since, lastSharedAt, lastSharedEventId,
                lastSharedTitle, monthsApart);
    }
}
