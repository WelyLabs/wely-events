package com.calendar.events.domain.models;

import java.time.Instant;

/**
 * One applied change of a pair, kept for the year in review: one per pair and version.
 *
 * @param closeness         the pair's closeness after the change; null when they are no longer friends
 * @param previousCloseness the closeness before it, when the record said
 */
public record FriendshipHistoryEntry(String pairId, String userA, String userB, FriendshipChangeType type,
                                     Closeness closeness, Closeness previousCloseness, Instant at, long version) {

    public static FriendshipHistoryEntry of(FriendshipChange change) {
        Friendship after = change.after();
        return new FriendshipHistoryEntry(after.pairId(), after.userA(), after.userB(), change.type(), after.closeness(),
                change.previousCloseness(), change.at(), after.version());
    }

    public boolean involves(String userId) {
        return userA.equals(userId) || userB.equals(userId);
    }

    /** The other side's id, seen from {@code userId}, who must be one of the two. */
    public String otherThan(String userId) {
        if (userId.equals(userA)) {
            return userB;
        }
        if (userId.equals(userB)) {
            return userA;
        }
        throw new IllegalArgumentException("Not part of the pair: " + userId);
    }

    /** A closeness change to a closer level (FRIEND < CLOSE < INTIMATE). */
    public boolean isUpgrade() {
        return type == FriendshipChangeType.CLOSENESS_CHANGED && closeness != null && previousCloseness != null
                && closeness.compareTo(previousCloseness) > 0;
    }
}
