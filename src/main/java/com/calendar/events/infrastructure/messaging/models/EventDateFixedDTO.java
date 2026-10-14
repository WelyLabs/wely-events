package com.calendar.events.infrastructure.messaging.models;

import java.time.Instant;

/**
 * Sent on {@code EVENT_DATE_FIXED} once the organizer picks a date in the poll, or moves the event,
 * so wely-chat can announce it in the event's group. Sent at least once: the consumer may see it twice.
 *
 * @param previousStart null for a date fixed by a poll; for a move, where the event was before it
 * @param previousEnd   for a move, the previous end, which may be null as an event's end may be
 */
public record EventDateFixedDTO(
        String eventId,
        String title,
        Instant start,
        Instant end,
        String organizerId,
        Instant previousStart,
        Instant previousEnd
) {}
