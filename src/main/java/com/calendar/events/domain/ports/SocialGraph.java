package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Closeness;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

/** Who a user is friends with, as the social graph knows it. */
public interface SocialGraph {

    Mono<Set<String>> friendIdsOf(String userId);

    /** How close the user is to this friend; empty if they are not friends. */
    Mono<Closeness> closenessBetween(String userId, String friendId);

    /** The user's friends and how close each is, in one call. */
    Mono<Map<String, Closeness>> friendsOf(String userId);
}
