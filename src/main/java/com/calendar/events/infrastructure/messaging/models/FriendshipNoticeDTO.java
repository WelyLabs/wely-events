package com.calendar.events.infrastructure.messaging.models;

/**
 * Sent on {@code FRIENDSHIP_NOTICES}, keyed by recipient, for wely-notifications to turn into a
 * notification. Sent at least once: the consumer may see it twice. Fields a type does not use are null.
 *
 * <ul>
 *   <li>REUNION_NUDGE: {@code friendId, friendName, closeness, lastSharedAt, lastSharedEventId, monthsApart};</li>
 *   <li>FRIENDSHIP_ANNIVERSARY: {@code friendId, friendName, closeness, years, since}.</li>
 * </ul>
 *
 * @param lastSharedAt ISO-8601 instant, null when the two never shared an outing
 * @param since        ISO-8601 instant
 */
public record FriendshipNoticeDTO(
        String type,
        String recipientId,
        String friendId,
        String friendName,
        String closeness,
        String lastSharedAt,
        String lastSharedEventId,
        Integer monthsApart,
        Integer years,
        String since
) {}
