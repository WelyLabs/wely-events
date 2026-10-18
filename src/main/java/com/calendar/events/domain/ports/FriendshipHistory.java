package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.FriendshipHistoryEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** Every change applied to the friendships projection, for the year in review. */
public interface FriendshipHistory {

    /** Appends the entry unless one for the same pair and version is there; true when appended. */
    Mono<Boolean> append(FriendshipHistoryEntry entry);

    /** The user's entries dated {@code from} or later, oldest first. */
    Flux<FriendshipHistoryEntry> involvingSince(String userId, Instant from);

    /** Drops every entry involving the user; returns how many. */
    Mono<Long> forget(String userId);
}
