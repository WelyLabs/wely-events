package com.calendar.events.application.dtos;

import java.time.Instant;

/** @param token what goes into {@code /events-service/public/calendar/<token>.ics}, until {@code expiresAt} */
public record CalendarLinkResponse(String token, Instant expiresAt) {}
