package com.calendar.events.domain.models;

/** How an expense is divided between the people it concerns. */
public enum SplitMode {
    /** The same amount each; the leftover cents go one by one, in a fixed order. */
    EQUAL,
    /** In proportion to each share's weight, e.g. 2 for a couple. */
    SHARES,
    /** Each share is an amount in cents, and they add up to the expense. */
    EXACT
}
