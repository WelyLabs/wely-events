package com.calendar.events.domain.ports;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;

/** Friends a user asked not to be reminded of for a while. */
public interface ReunionSnoozes {

    /** Snoozes the friend for the user until {@code until}, replacing any earlier snooze of the pair. */
    Mono<Void> snooze(String userId, String friendId, Instant until);

    /** The friends the user snoozed that are still snoozed at {@code now}. */
    Mono<Set<String>> snoozedBy(String userId, Instant now);

    /** Drops every snooze by or about the user. */
    Mono<Void> forget(String userId);
}
