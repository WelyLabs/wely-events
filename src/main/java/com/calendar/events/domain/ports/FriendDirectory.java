package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Closeness;
import reactor.core.publisher.Mono;

import java.util.Set;

/** Who a user is friends with, as the social graph knows it. */
public interface FriendDirectory {

    Mono<Set<String>> findFriendIds(String userId);

    /** How close the user is to this friend; empty if they are not friends. */
    Mono<Closeness> findCloseness(String userId, String friendId);
}
