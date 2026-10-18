package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.Friendship;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Every friendship wely-social announced, for work done on nobody's behalf, without a token to ask it with. */
public interface FriendshipDirectory {

    /** The user's current friends. */
    Flux<Friend> friendsOf(String userId);

    /** Stores the pair's new state unless one as new or newer is already stored; true when stored. */
    Mono<Boolean> record(Friendship friendship);

    /** Drops every pair involving the user, past ones included; returns how many. */
    Mono<Long> forget(String userId);
}
