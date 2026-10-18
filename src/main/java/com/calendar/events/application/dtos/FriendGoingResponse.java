package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Closeness;

/** One of the caller's friends going to an event. Names are resolved by the client. */
public record FriendGoingResponse(String userId, Closeness closeness) {
}
