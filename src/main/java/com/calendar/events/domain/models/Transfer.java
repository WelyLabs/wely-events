package com.calendar.events.domain.models;

/** A payment that would settle part of the balances. */
public record Transfer(String fromUserId, String toUserId, long amountCents) {}
