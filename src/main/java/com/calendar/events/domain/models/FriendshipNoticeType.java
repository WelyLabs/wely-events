package com.calendar.events.domain.models;

/** The friendship notices wely-notifications turns into notifications. */
public enum FriendshipNoticeType {
    /** To a user who has not been out with a close friend for a while. */
    REUNION_NUDGE,
    /** To both friends, every year on the day they became friends. */
    FRIENDSHIP_ANNIVERSARY
}
