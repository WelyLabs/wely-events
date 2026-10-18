package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.ports.FriendshipDirectory;
import reactor.core.publisher.Mono;

import java.util.List;

/** Keeps the friendships wely-social announces, for the birthday reminders and surprise announcements. */
public class FriendshipService {

    private final FriendshipDirectory directory;
    private final AccountGuard accountGuard;

    public FriendshipService(FriendshipDirectory directory, AccountGuard accountGuard) {
        this.directory = directory;
        this.accountGuard = accountGuard;
    }

    /** Stores the change unless it is stale or names a deleted account, which must not get friends back; true when stored. */
    public Mono<Boolean> record(Friendship friendship) {
        return accountGuard.deletedAmong(List.of(friendship.userA(), friendship.userB()))
                .flatMap(deleted -> deleted.isEmpty() ? directory.record(friendship) : Mono.just(false));
    }
}
