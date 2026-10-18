package com.calendar.events.domain.models;

import java.util.Objects;

/**
 * A stored pair, and the version of the last FRIENDSHIP_STATE value read for it, applied or stale.
 *
 * @param streamedVersion null for a pair stored before FRIENDSHIP_STATE was read
 */
public record TrackedPair(Friendship pair, Long streamedVersion) {

    public TrackedPair {
        Objects.requireNonNull(pair, "pair");
    }

    /**
     * Whether a tombstone read now ends the stored friendship. A tombstone carries no version: it ends the
     * value read just before it for the same key. When that value was stale, the tombstone ended an older
     * state, not the stored one, and must not undo it (a replay over a projection already newer).
     */
    public boolean endedByTombstone() {
        return pair.friends() && (streamedVersion == null || streamedVersion == pair.version());
    }
}
