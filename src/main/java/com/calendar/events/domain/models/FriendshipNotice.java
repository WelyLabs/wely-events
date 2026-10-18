package com.calendar.events.domain.models;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One notice for wely-notifications, recorded at most once under its {@link #key()}. Fields a type does
 * not use are null.
 *
 * @param day the day it is due on, in the users' time zone
 */
public record FriendshipNotice(
        FriendshipNoticeType type,
        String recipientId,
        String friendId,
        String friendName,
        Closeness closeness,
        Instant lastSharedAt,
        String lastSharedEventId,
        Integer monthsApart,
        Integer years,
        Instant since,
        LocalDate day
) {

    public FriendshipNotice {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(friendId, "friendId");
        Objects.requireNonNull(day, "day");
    }

    /** The nudge about this candidate, due on {@code day}. */
    public static FriendshipNotice reunionNudge(String recipientId, ReunionCandidate candidate, LocalDate day) {
        return new FriendshipNotice(FriendshipNoticeType.REUNION_NUDGE, recipientId, candidate.friendId(),
                candidate.name(), candidate.closeness(), candidate.lastSharedAt(), candidate.lastSharedEventId(),
                candidate.monthsApart(), null, null, day);
    }

    /** The anniversary of this friendship, for one of the two. */
    public static FriendshipNotice anniversary(String recipientId, String friendId, String friendName,
                                               Closeness closeness, int years, Instant since, LocalDate day) {
        return new FriendshipNotice(FriendshipNoticeType.FRIENDSHIP_ANNIVERSARY, recipientId, friendId, friendName,
                closeness, null, null, null, years, since, day);
    }

    /**
     * The idempotency key: one nudge per recipient and day, so two runs or two pods never send two; one
     * anniversary per recipient, friend and year of friendship.
     */
    public String key() {
        return switch (type) {
            case REUNION_NUDGE -> type.name() + ":" + recipientId + ":" + day;
            case FRIENDSHIP_ANNIVERSARY -> type.name() + ":" + recipientId + ":" + friendId + ":" + years;
        };
    }
}
