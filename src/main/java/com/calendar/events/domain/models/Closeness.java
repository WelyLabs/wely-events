package com.calendar.events.domain.models;

/**
 * How close two friends are, as wely-social records it (FRIEND < CLOSE < INTIMATE). Mirrored
 * here rather than shared, like everything across the services.
 */
public enum Closeness {
    FRIEND,
    CLOSE,
    INTIMATE
}
