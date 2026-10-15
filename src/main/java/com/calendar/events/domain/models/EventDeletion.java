package com.calendar.events.domain.models;

import java.time.Instant;

/**
 * A deleted event, kept until wely-chat has been told to close its group.
 *
 * @param version one past the event's last group version, so it outranks every member list sent before
 */
public record EventDeletion(String eventId, String title, long version, Instant deletedAt) {

    /** The tombstone for an event just deleted; one stored before group versions counts as version 0. */
    public static EventDeletion of(Event deleted, Instant deletedAt) {
        long lastVersion = deleted.getGroupVersion() != null ? deleted.getGroupVersion() : 0L;
        return new EventDeletion(deleted.getId(), deleted.getTitle(), lastVersion + 1, deletedAt);
    }
}
