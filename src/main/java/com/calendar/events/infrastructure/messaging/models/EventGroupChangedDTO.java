package com.calendar.events.infrastructure.messaging.models;

import java.util.List;

/**
 * An event's group as it travels on {@code EVENT_GROUP_CHANGED}: the whole member list, not
 * what changed. Applying it twice, or after a newer one, is harmless on the consuming side —
 * which is what lets the relay send at least once without coordinating with wely-chat.
 *
 * @param memberIds the organizer and everyone taking part; empty once nobody does
 * @param version   grows with every change; wely-chat ignores a list older than the one it has
 */
public record EventGroupChangedDTO(
        String eventId,
        String title,
        List<String> memberIds,
        long version
) {}
