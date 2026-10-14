package com.calendar.events.infrastructure.messaging.models;

import java.time.Instant;

/**
 * Sent on {@code EVENT_DATE_FIXED} once the organizer picks a date in the poll, so wely-chat can
 * announce it in the event's group. Sent at least once: the consumer may see it twice.
 */
public record EventDateFixedDTO(
        String eventId,
        String title,
        Instant start,
        Instant end,
        String organizerId
) {}
