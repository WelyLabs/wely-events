package com.calendar.events.domain.models;

/** What happened to a pair, as wely-social's {@code FRIENDSHIP_CHANGED} names it. */
public enum FriendshipChangeType {
    REQUESTED(false),
    ACCEPTED(true),
    REJECTED(false),
    CANCELLED(false),
    REMOVED(false),
    CLOSENESS_CHANGED(true),
    /** wely-social's one-time backfill of a friendship that already existed. */
    SNAPSHOT(true);

    private final boolean friendsAfter;

    FriendshipChangeType(boolean friendsAfter) {
        this.friendsAfter = friendsAfter;
    }

    /** Whether the two are friends once this has happened. */
    public boolean friendsAfter() {
        return friendsAfter;
    }
}
