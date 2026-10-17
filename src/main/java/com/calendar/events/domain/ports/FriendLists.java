package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.FriendList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The friend lists users were last given by wely-social, for work done without their token. */
public interface FriendLists {

    /** Replaces the user's list. */
    Mono<Void> save(FriendList list);

    Mono<FriendList> findOf(String userId);

    /** Every other user's list naming this user as a friend. */
    Flux<FriendList> findNaming(String userId);

    Mono<Void> delete(String userId);
}
