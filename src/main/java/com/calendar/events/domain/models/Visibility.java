package com.calendar.events.domain.models;

/** Who may see an event besides the people taking part in it. */
public enum Visibility {
    /** Everyone. */
    PUBLIC,
    /** The organizer's friends. Also what an event stored before visibility existed counts as. */
    FRIENDS,
    /** Nobody else: in a friend's calendar it only ever shows as busy. */
    PRIVATE;

    public static final Visibility DEFAULT = FRIENDS;
}
