package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.FriendshipNoticeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** Friendship notices for wely-notifications, each kept under its key so it is sent at most once. */
public interface FriendshipNoticeOutbox {

    /** Records the notice for sending; false when one with the same key was recorded before. */
    Mono<Boolean> record(FriendshipNotice notice);

    /** The notices of this type recorded for the recipient from {@code since} on, sent or not, newest first. */
    Flux<FriendshipNotice> recordedFor(String recipientId, FriendshipNoticeType type, Instant since);

    /** Notices not sent yet, oldest first, at most {@code limit}. */
    Flux<FriendshipNotice> findPending(int limit);

    /** Marks the notice sent; its key stays, so it is never recorded again. */
    Mono<Void> markPublished(String key);

    /** Drops the notices not sent yet that are for or about the user. */
    Mono<Void> forget(String userId);
}
