package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipChange;
import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.FriendshipHistory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Applies the friendship changes wely-social announces: the projection every path reads, the history
 * the year in review reads, and the surprises a friendship that ends takes with it.
 */
public class FriendshipService {

    private final FriendshipDirectory directory;
    private final FriendshipHistory history;
    private final SurpriseService surpriseService;
    private final AccountGuard accountGuard;
    private final Clock clock;

    public FriendshipService(FriendshipDirectory directory, FriendshipHistory history, SurpriseService surpriseService,
                             AccountGuard accountGuard, Clock clock) {
        this.directory = directory;
        this.history = history;
        this.surpriseService = surpriseService;
        this.accountGuard = accountGuard;
        this.clock = clock;
    }

    /**
     * Applies the change unless it is stale or names a deleted account, which must not get friends back;
     * true when applied. Each step is safe to replay, and the pair is written last: a failure midway
     * leaves it stale, so the retry runs the steps again.
     */
    public Mono<Boolean> record(FriendshipChange change) {
        Friendship after = change.after();
        return accountGuard.deletedAmong(List.of(after.userA(), after.userB()))
                .flatMap(deleted -> deleted.isEmpty() ? apply(change.datedOrAt(clock.instant())) : Mono.just(false));
    }

    private Mono<Boolean> apply(FriendshipChange change) {
        Friendship after = change.after();
        return directory.pairOf(after.userA(), after.userB())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(previous -> {
                    if (previous.isPresent() && previous.get().version() >= after.version()) {
                        return Mono.just(false);
                    }
                    boolean ended = previous.map(Friendship::friends).orElse(false) && !after.friends();
                    Mono<Long> cleanup = ended
                            ? surpriseService.releaseAfterFriendshipEnded(after.userA(), after.userB())
                            : Mono.just(0L);
                    return cleanup
                            .then(Mono.defer(() -> history.append(FriendshipHistoryEntry.of(change))))
                            .then(Mono.defer(() -> directory.record(after)));
                });
    }
}
