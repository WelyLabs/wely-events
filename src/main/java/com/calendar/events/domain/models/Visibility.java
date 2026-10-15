package com.calendar.events.domain.models;

/** Who may see an event besides the people taking part in it. */
public enum Visibility {
    /** Everyone. */
    PUBLIC,
    /** The organizer's friends. Also what an event stored before visibility existed counts as. */
    FRIENDS,
    /** Nobody else: in a friend's calendar it only ever shows as busy. */
    PRIVATE,
    /**
     * The members of the event's community, and nobody else: set by the domain on every event
     * published in a community, never chosen. Membership lives in wely-social, so the event itself
     * cannot tell; see {@code EventService}.
     */
    COMMUNITY;

    public static final Visibility DEFAULT = FRIENDS;
}
