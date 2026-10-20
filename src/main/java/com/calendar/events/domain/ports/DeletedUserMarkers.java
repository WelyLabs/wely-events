package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.DeletedUserMarker;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/** The permanent register of accounts wely-users deleted; a store failure arrives as the technical STORE_UNAVAILABLE. */
public interface DeletedUserMarkers {

    /** Records the deletion if absent and returns the stored marker: the first deletedAt wins. */
    Mono<DeletedUserMarker> markDeleted(String userId, Instant deletedAt);

    Mono<Boolean> isDeleted(String userId);

    /** The ones among {@code userIds} that have a marker; no query for an empty input. */
    Mono<Set<String>> deletedAmong(Collection<String> userIds);

    /** Records that the erasure completed, keeping the latest time. */
    Mono<Void> markErased(String userId, Instant erasedAt);

    /** The ones among {@code userIds} whose erasure completed; no query for an empty input. */
    Mono<Set<String>> erasedAmong(Collection<String> userIds);
}
