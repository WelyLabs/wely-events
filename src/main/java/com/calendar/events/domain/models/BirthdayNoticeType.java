package com.calendar.events.domain.models;

/** The birthday notices wely-notifications turns into notifications. */
public enum BirthdayNoticeType {
    /** To the honoree, on the day. */
    BIRTHDAY_TODAY,
    /** To every friend, on the day. */
    FRIEND_BIRTHDAY,
    /** To close and intimate friends ahead of the day: organize, or join, a surprise. */
    SURPRISE_PROPOSAL,
    /** To the honoree's other close and intimate friends, once someone organizes a surprise. */
    SURPRISE_CREATED
}
