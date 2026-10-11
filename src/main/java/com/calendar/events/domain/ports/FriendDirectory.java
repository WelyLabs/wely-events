package com.calendar.events.domain.ports;

import reactor.core.publisher.Mono;

import java.util.Set;

/** Who a user is friends with, as the social graph knows it. */
public interface FriendDirectory {

    Mono<Set<String>> findFriendIds(String userId);
}
