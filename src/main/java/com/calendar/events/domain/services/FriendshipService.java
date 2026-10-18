package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipChange;
import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.domain.models.TrackedPair;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.FriendshipHistory;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import com.calendar.events.domain.ports.ReunionSnoozes;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Applies what wely-social announces: FRIENDSHIP_STATE feeds the projection every path reads and releases
 * the surprises a friendship that ends takes with it; FRIENDSHIP_CHANGED feeds the history the year in
 * review reads.
 */
public class FriendshipService {

    private final FriendshipDirectory directory;
    private final FriendshipHistory history;
    private final FriendshipNoticeOutbox noticeOutbox;
    private final ReunionSnoozes snoozes;
    private final SurpriseService surpriseService;
    private final AccountGuard accountGuard;
    private final Clock clock;

    public FriendshipService(FriendshipDirectory directory, FriendshipHistory history, FriendshipNoticeOutbox noticeOutbox,
                             ReunionSnoozes snoozes, SurpriseService surpriseService, AccountGuard accountGuard,
                             Clock clock) {
        this.directory = directory;
        this.history = history;
        this.noticeOutbox = noticeOutbox;
        this.snoozes = snoozes;
        this.surpriseService = surpriseService;
        this.accountGuard = accountGuard;
        this.clock = clock;
    }

    /**
     * Erases what this service holds of an account's friendships: every pair, past ones included, its
     * history, its pending friendship notices and its snoozes. Safe to replay; runs after the deleted-account
     * marker, which keeps a late FRIENDSHIP_STATE value from storing a pair again.
     */
    public Mono<Void> forget(String userId) {
        return Mono.defer(() -> directory.forget(userId))
                .then(Mono.defer(() -> history.forget(userId)))
                .then(Mono.defer(() -> noticeOutbox.forget(userId)))
                .then(Mono.defer(() -> snoozes.forget(userId)));
    }

    /** The viewer's friends and how close each is, read once for a whole response. */
    public Mono<ViewerFriends> viewerFriends(String viewerId) {
        return directory.friendsOf(viewerId)
                .collectMap(Friend::userId, Friend::closeness)
                .map(ViewerFriends::new);
    }

    /**
     * Applies a FRIENDSHIP_STATE value unless it is stale or names a deleted account, which must not get
     * friends back; true when applied. A stale one is still noted as the last streamed, for the tombstone
     * that may follow it. Each step is safe to replay, and the pair is written last: a failure midway leaves
     * it stale, so the retry runs the steps again.
     */
    public Mono<Boolean> applyState(Friendship state) {
        return accountGuard.deletedAmong(List.of(state.userA(), state.userB()))
                .flatMap(deleted -> deleted.isEmpty() ? apply(state) : Mono.just(false));
    }

    /**
     * Applies a FRIENDSHIP_STATE tombstone: the pair is no longer friends. Ends the stored friendship only
     * when the value just before it was the stored one (see {@link TrackedPair#endedByTombstone()}); true
     * when ended. The surprises are released before the pair is written, so a retry releases again, which
     * is safe, and once the pair is ended a replayed tombstone releases nothing: once per transition.
     */
    public Mono<Boolean> applyTombstone(String userId, String otherId) {
        return directory.trackedPairOf(userId, otherId)
                .filter(TrackedPair::endedByTombstone)
                .map(TrackedPair::pair)
                .flatMap(current -> surpriseService.releaseAfterFriendshipEnded(current.userA(), current.userB())
                        .then(Mono.defer(() -> directory.end(current))))
                .defaultIfEmpty(false);
    }

    /**
     * Appends a FRIENDSHIP_CHANGED record to the history unless it names a deleted account; true when
     * appended. Never touches the projection, which FRIENDSHIP_STATE feeds.
     */
    public Mono<Boolean> recordHistory(FriendshipChange change) {
        Friendship after = change.after();
        return accountGuard.deletedAmong(List.of(after.userA(), after.userB()))
                .flatMap(deleted -> deleted.isEmpty()
                        ? history.append(FriendshipHistoryEntry.of(change.datedOrAt(clock.instant())))
                        : Mono.just(false));
    }

    private Mono<Boolean> apply(Friendship state) {
        return directory.trackedPairOf(state.userA(), state.userB())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(previous -> {
                    if (previous.isPresent() && previous.get().pair().version() >= state.version()) {
                        return directory.markStreamed(state.userA(), state.userB(), state.version()).thenReturn(false);
                    }
                    boolean ended = previous.map(stored -> stored.pair().friends()).orElse(false) && !state.friends();
                    Mono<Long> cleanup = ended
                            ? surpriseService.releaseAfterFriendshipEnded(state.userA(), state.userB())
                            : Mono.just(0L);
                    return cleanup.then(Mono.defer(() -> directory.record(state)));
                });
    }
}
