package com.calendar.events.infrastructure.messaging.models;

/**
 * Sent on {@code BIRTHDAY_NOTICES}, keyed by recipient, for wely-notifications to turn into a
 * notification. Sent at least once: the consumer may see it twice.
 *
 * @param date      the birthday's occurrence, {@code YYYY-MM-DD}
 * @param closeness FRIEND, CLOSE or INTIMATE; null for the honoree themselves
 */
public record BirthdayNoticeDTO(
        String type,
        String recipientId,
        String honoreeId,
        String honoreeName,
        String date,
        int daysUntil,
        String closeness,
        String surpriseEventId,
        String organizerId,
        String organizerName
) {}
