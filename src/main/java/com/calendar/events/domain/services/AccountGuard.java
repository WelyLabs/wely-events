package com.calendar.events.domain.services;

import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Set;

/** Reads the deleted-accounts register for whatever must not act for, or count, a deleted account. */
public class AccountGuard {

    private final DeletedUserMarkers markers;

    public AccountGuard(DeletedUserMarkers markers) {
        this.markers = markers;
    }

    public Mono<Boolean> isDeleted(String userId) {
        return markers.isDeleted(userId);
    }

    /** Fails with ACCOUNT_DELETED for a deleted account, completes empty otherwise. */
    public Mono<Void> requireLive(String userId) {
        return markers.isDeleted(userId)
                .flatMap(deleted -> deleted
                        ? Mono.<Void>error(new EventException(EventErrorCode.ACCOUNT_DELETED))
                        : Mono.<Void>empty());
    }

    /** The deleted accounts among {@code userIds}; no lookup for an empty input. */
    public Mono<Set<String>> deletedAmong(Collection<String> userIds) {
        return userIds.isEmpty() ? Mono.just(Set.of()) : markers.deletedAmong(userIds);
    }
}
