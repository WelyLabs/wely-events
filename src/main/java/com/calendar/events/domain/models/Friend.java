package com.calendar.events.domain.models;

/** One of a user's friends, and how close the two are. */
public record Friend(String userId, Closeness closeness) {
}
