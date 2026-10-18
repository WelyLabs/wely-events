package com.calendar.events.domain.models;

import java.time.Instant;
import java.util.Objects;

/**
 * One FRIENDSHIP_CHANGED record as this service applies it: the pair after the change, and what it was.
 *
 * @param previousCloseness the pair's closeness before the change; null when unknown or not friends before
 * @param at                when wely-social says it happened; null when the record did not say
 */
public record FriendshipChange(FriendshipChangeType type, Friendship after, Closeness previousCloseness, Instant at) {

    public FriendshipChange {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(after, "after");
    }

    /** This change, dated {@code fallback} when the record carried no date. */
    public FriendshipChange datedOrAt(Instant fallback) {
        return at != null ? this : new FriendshipChange(type, after, previousCloseness, fallback);
    }
}
