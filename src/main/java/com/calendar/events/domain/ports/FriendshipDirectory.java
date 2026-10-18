package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.Friendship;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Every friendship wely-social announced, kept locally so no path needs to ask wely-social. */
public interface FriendshipDirectory {

    /** The user's current friends. */
    Flux<Friend> friendsOf(String userId);

    /** The user's current friendships, with when each began. */
    Flux<Friendship> friendshipsOf(String userId);

    /** Every pair currently friends, for the daily reminders. */
    Flux<Friendship> allFriendships();

    /** The pair as last recorded, friends or not; empty when never recorded or for oneself. */
    Mono<Friendship> pairOf(String userId, String otherId);

    /** Stores the pair's new state unless one as new or newer is already stored; true when stored. */
    Mono<Boolean> record(Friendship friendship);

    /** Drops every pair involving the user, past ones included; returns how many. */
    Mono<Long> forget(String userId);
}
