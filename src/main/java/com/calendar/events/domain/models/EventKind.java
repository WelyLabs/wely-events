package com.calendar.events.domain.models;

/** What an event is for; read through {@code Event#effectiveKind()}, as events stored before it have none. */
public enum EventKind {
    /** An outing anyone creates. */
    STANDARD,
    /** A user's yearly birthday: one per user, kept by the USER_BIRTHDAY consumer, never through the API. */
    BIRTHDAY,
    /** A birthday party organized behind the honoree's back: never shown to them. */
    SURPRISE
}
