package com.calendar.events.infrastructure.realtime.models;

/**
 * A record of the poll-updates topic. It names what changed, never the poll itself: each pod reads
 * the current poll from MongoDB, so two votes sent from two pods cannot arrive in the wrong order.
 *
 * @param kind     {@link #CHANGED}, with {@code eventId}, or {@link #DISCONNECTED}, with {@code viewerId}
 */
public record PollUpdateBroadcastDTO(String kind, String eventId, String viewerId) {

    public static final String CHANGED = "CHANGED";
    public static final String DISCONNECTED = "DISCONNECTED";

    public static PollUpdateBroadcastDTO changed(String eventId) {
        return new PollUpdateBroadcastDTO(CHANGED, eventId, null);
    }

    public static PollUpdateBroadcastDTO disconnected(String viewerId) {
        return new PollUpdateBroadcastDTO(DISCONNECTED, null, viewerId);
    }
}
