package com.calendar.events.domain.models;

/** One person's part of an expense: a weight in {@link SplitMode#SHARES}, cents in {@link SplitMode#EXACT}, unused in {@link SplitMode#EQUAL}. */
public record Share(String userId, long value) {}
