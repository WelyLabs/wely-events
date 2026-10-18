package com.calendar.events.domain.models;

import java.time.Instant;
import java.util.Objects;

/**
 * A pair of users as wely-social last described it. Kept once they are no longer friends, so a late,
 * older record is still recognized by its {@code version}, which wely-social raises with every change.
 */
public record Friendship(String userA, String userB, boolean friends, Closeness closeness, Instant since, long version) {

    static final String PAIR_SEPARATOR = ":";

    /** Sorts the pair, so either side's record lands on the same one; non-friends carry no closeness. */
    public Friendship {
        Objects.requireNonNull(userA, "userA");
        Objects.requireNonNull(userB, "userB");
        if (userA.equals(userB)) {
            throw new IllegalArgumentException("A friendship needs two users");
        }
        if (userA.compareTo(userB) > 0) {
            String first = userB;
            userB = userA;
            userA = first;
        }
        if (friends && closeness == null) {
            throw new IllegalArgumentException("Friends need a closeness");
        }
        if (!friends) {
            closeness = null;
            since = null;
        }
    }

    /** The pair once a change of this type has happened. */
    public static Friendship after(FriendshipChangeType type, String fromUserId, String toUserId, Closeness closeness,
                                   Instant since, long version) {
        return new Friendship(fromUserId, toUserId, type.friendsAfter(), closeness, since, version);
    }

    /** The two ids, sorted and joined: the pair's identity, and wely-social's partition key. */
    public String pairId() {
        return userA + PAIR_SEPARATOR + userB;
    }

    /** The other side, seen from {@code userId}, who must be one of the two. */
    public Friend friendOf(String userId) {
        if (userId.equals(userA)) {
            return new Friend(userB, closeness);
        }
        if (userId.equals(userB)) {
            return new Friend(userA, closeness);
        }
        throw new IllegalArgumentException("Not part of the pair: " + userId);
    }
}
