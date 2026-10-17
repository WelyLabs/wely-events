package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendList;
import com.calendar.events.domain.ports.FriendLists;
import com.calendar.events.domain.ports.SocialGraph;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Who a user's friends are, for work done on nobody's behalf. wely-social answers "my friends" for
 * the token's owner only, so each answer a caller gets is remembered, and another user's friends
 * are read back from every remembered list. A pair neither side has asked about since is unknown:
 * the price of not adding an internal endpoint to wely-social.
 */
public class FriendDirectory {

    private static final System.Logger LOG = System.getLogger(FriendDirectory.class.getName());

    private final SocialGraph socialGraph;
    private final FriendLists friendLists;
    private final Clock clock;

    public FriendDirectory(SocialGraph socialGraph, FriendLists friendLists, Clock clock) {
        this.socialGraph = socialGraph;
        this.friendLists = friendLists;
        this.clock = clock;
    }

    /** The caller's friends from wely-social, remembered on the way; a failed write costs only the memory. */
    public Mono<Map<String, Closeness>> refreshFriendsOf(String callerId) {
        return socialGraph.friendsOf(callerId)
                .defaultIfEmpty(Map.of())
                .flatMap(friends -> Mono.defer(() -> friendLists.save(new FriendList(callerId, friends, clock.instant())))
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Friend list of {0} not remembered: {1}",
                                    callerId, error.toString());
                            return Mono.empty();
                        })
                        .thenReturn(friends));
    }

    /** The user's friends as the remembered lists know them; empty when nobody's list names them. */
    public Mono<Map<String, Closeness>> knownFriendsOf(String userId) {
        return Mono.zip(
                        friendLists.findOf(userId).map(Optional::of).defaultIfEmpty(Optional.empty()),
                        friendLists.findNaming(userId).collectList())
                .map(found -> FriendList.friendsOf(userId, found.getT1().orElse(null), found.getT2()));
    }

    public Mono<Void> forget(String userId) {
        return friendLists.delete(userId);
    }
}
