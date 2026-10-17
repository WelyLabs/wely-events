package com.calendar.events.domain.models;

import java.time.LocalDate;

/**
 * One notice for wely-notifications, sent at most once per honoree, recipient, birthday year and type.
 *
 * @param date            the birthday's occurrence, which wely-notifications shows and links to
 * @param daysUntil       days from the sending day to {@code date}, 0 on the day
 * @param closeness       how close the recipient is to the honoree; null for the honoree themselves
 * @param surpriseEventId the surprise already organized for this occurrence, or null
 */
public record BirthdayNotice(
        BirthdayNoticeType type,
        String recipientId,
        String honoreeId,
        String honoreeName,
        LocalDate date,
        int daysUntil,
        Closeness closeness,
        String surpriseEventId,
        String organizerId,
        String organizerName
) {

    /** The idempotency key: a notice already recorded under it is never sent again. */
    public String key() {
        return honoreeId + ":" + recipientId + ":" + date.getYear() + ":" + type.name();
    }
}
