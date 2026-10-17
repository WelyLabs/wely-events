package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.BirthdayNotice;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Birthday notices for wely-notifications, each kept under its key so it is sent at most once. */
public interface BirthdayNoticeOutbox {

    /** Records the notice for sending; false when one with the same key was recorded before. */
    Mono<Boolean> record(BirthdayNotice notice);

    /** Notices not sent yet, oldest first, at most {@code limit}. */
    Flux<BirthdayNotice> findPending(int limit);

    /** Marks the notice sent; its key stays, so it is never recorded again. */
    Mono<Void> markPublished(String key);

    /** Drops the notices not sent yet that are for or about the user. */
    Mono<Void> forget(String userId);
}
