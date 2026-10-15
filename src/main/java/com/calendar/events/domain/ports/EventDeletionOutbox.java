package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.EventDeletion;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Deleted events wely-chat has not been told about yet: the event document is gone, so its outbox with it. */
public interface EventDeletionOutbox {

    Mono<Void> record(EventDeletion deletion);

    /** The oldest deletions first, at most {@code limit}. */
    Flux<EventDeletion> findPending(int limit);

    /** Drops the tombstone once the deletion has been sent. */
    Mono<Void> markPublished(String eventId);
}
