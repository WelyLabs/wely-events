package com.calendar.events.domain.models;

/** A shared cost, or money handed back to settle a debt. */
public enum ExpenseKind {
    EXPENSE,
    /** Paid by whoever owed, wholly for whoever is owed: it moves both balances towards zero. */
    SETTLEMENT
}
