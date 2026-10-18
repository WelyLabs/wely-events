package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipChange;
import com.calendar.events.domain.models.FriendshipChangeType;
import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.domain.models.TrackedPair;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.FriendshipHistory;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import com.calendar.events.domain.ports.ReunionSnoozes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipServiceTest {

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-18T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final Friendship FRIENDS = new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 7);
    private static final FriendshipChange UPGRADE =
            new FriendshipChange(FriendshipChangeType.CLOSENESS_CHANGED, FRIENDS, Closeness.FRIEND, AT);
    private static final FriendshipChange REMOVAL = new FriendshipChange(FriendshipChangeType.REMOVED,
            new Friendship("alice", "bob", false, null, null, 8), Closeness.CLOSE, AT);

    @Mock
    private FriendshipDirectory directory;
    @Mock
    private FriendshipHistory history;
    @Mock
    private FriendshipNoticeOutbox noticeOutbox;
    @Mock
    private ReunionSnoozes snoozes;
    @Mock
    private SurpriseService surpriseService;
    @Mock
    private DeletedUserMarkers markers;

    private FriendshipService service;

    @BeforeEach
    void setUp() {
        service = new FriendshipService(directory, history, noticeOutbox, snoozes, surpriseService,
                new AccountGuard(markers), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void nobodyDeleted() {
        when(markers.deletedAmong(List.of("alice", "bob"))).thenReturn(Mono.just(Set.of()));
    }

    private static TrackedPair tracked(Friendship pair, Long streamedVersion) {
        return new TrackedPair(pair, streamedVersion);
    }

    @Test
    void applyState_shouldStoreANewerVersion_withoutReleasingAnything() {
        nobodyDeleted();
        when(directory.trackedPairOf("alice", "bob"))
                .thenReturn(Mono.just(tracked(new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 6), 6L)));
        when(directory.record(FRIENDS)).thenReturn(Mono.just(true));

        StepVerifier.create(service.applyState(FRIENDS)).expectNext(true).verifyComplete();
        verify(surpriseService, never()).releaseAfterFriendshipEnded(anyString(), anyString());
        verify(history, never()).append(any());
    }

    @Test
    void applyState_shouldStoreANewPair() {
        nobodyDeleted();
        when(directory.trackedPairOf("alice", "bob")).thenReturn(Mono.empty());
        when(directory.record(FRIENDS)).thenReturn(Mono.just(true));

        StepVerifier.create(service.applyState(FRIENDS)).expectNext(true).verifyComplete();
    }

    @Test
    void applyState_shouldOnlyNoteAStaleOrRepeatedVersionAsStreamed() {
        nobodyDeleted();
        when(directory.trackedPairOf("alice", "bob"))
                .thenReturn(Mono.just(tracked(new Friendship("alice", "bob", true, Closeness.INTIMATE, SINCE, 9), 9L)));
        when(directory.markStreamed("alice", "bob", 7)).thenReturn(Mono.empty());

        StepVerifier.create(service.applyState(FRIENDS)).expectNext(false).verifyComplete();
        verify(directory).markStreamed("alice", "bob", 7);
        verify(directory, never()).record(any());
    }

    @Test
    void applyState_shouldReleaseTheSurprisesFirst_whenANewerValueEndsTheFriendship() {
        nobodyDeleted();
        Friendship parted = new Friendship("alice", "bob", false, null, null, 8);
        when(directory.trackedPairOf("alice", "bob")).thenReturn(Mono.just(tracked(FRIENDS, 7L)));
        when(surpriseService.releaseAfterFriendshipEnded("alice", "bob")).thenReturn(Mono.just(1L));
        when(directory.record(parted)).thenReturn(Mono.just(true));

        StepVerifier.create(service.applyState(parted)).expectNext(true).verifyComplete();
        InOrder order = inOrder(surpriseService, directory);
        order.verify(surpriseService).releaseAfterFriendshipEnded("alice", "bob");
        order.verify(directory).record(parted);
    }

    @Test
    void applyState_shouldIgnoreAPairNamingADeletedAccount() {
        when(markers.deletedAmong(List.of("alice", "bob"))).thenReturn(Mono.just(Set.of("bob")));

        StepVerifier.create(service.applyState(FRIENDS)).expectNext(false).verifyComplete();
        verify(directory, never()).trackedPairOf(anyString(), anyString());
        verify(directory, never()).record(any());
    }

    @Test
    void applyState_shouldFail_whenTheRegisterCannotBeRead() {
        when(markers.deletedAmong(List.of("alice", "bob"))).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.applyState(FRIENDS)).expectError(IllegalStateException.class).verify();
        verify(directory, never()).record(any());
    }

    @Test
    void applyTombstone_shouldReleaseTheSurprises_thenEndThePair() {
        when(directory.trackedPairOf("bob", "alice")).thenReturn(Mono.just(tracked(FRIENDS, 7L)));
        when(surpriseService.releaseAfterFriendshipEnded("alice", "bob")).thenReturn(Mono.just(2L));
        when(directory.end(FRIENDS)).thenReturn(Mono.just(true));

        StepVerifier.create(service.applyTombstone("bob", "alice")).expectNext(true).verifyComplete();
        InOrder order = inOrder(surpriseService, directory);
        order.verify(surpriseService).releaseAfterFriendshipEnded("alice", "bob");
        order.verify(directory).end(FRIENDS);
    }

    @Test
    void applyTombstone_shouldEndAPairStoredBeforeTheStateTopic() {
        when(directory.trackedPairOf("alice", "bob")).thenReturn(Mono.just(tracked(FRIENDS, null)));
        when(surpriseService.releaseAfterFriendshipEnded("alice", "bob")).thenReturn(Mono.just(0L));
        when(directory.end(FRIENDS)).thenReturn(Mono.just(true));

        StepVerifier.create(service.applyTombstone("alice", "bob")).expectNext(true).verifyComplete();
    }

    @Test
    void applyTombstone_shouldReleaseNothing_whenThePairIsAlreadyNotFriends() {
        when(directory.trackedPairOf("alice", "bob"))
                .thenReturn(Mono.just(tracked(new Friendship("alice", "bob", false, null, null, 7), 7L)));

        StepVerifier.create(service.applyTombstone("alice", "bob")).expectNext(false).verifyComplete();
        verify(surpriseService, never()).releaseAfterFriendshipEnded(anyString(), anyString());
        verify(directory, never()).end(any());
    }

    @Test
    void applyTombstone_shouldKeepTheStoredFriendship_whenTheValueBeforeItWasStale() {
        when(directory.trackedPairOf("alice", "bob")).thenReturn(Mono.just(tracked(FRIENDS, 3L)));

        StepVerifier.create(service.applyTombstone("alice", "bob")).expectNext(false).verifyComplete();
        verify(surpriseService, never()).releaseAfterFriendshipEnded(anyString(), anyString());
        verify(directory, never()).end(any());
    }

    @Test
    void applyTombstone_shouldDoNothing_forAnUnknownPair() {
        when(directory.trackedPairOf("alice", "bob")).thenReturn(Mono.empty());

        StepVerifier.create(service.applyTombstone("alice", "bob")).expectNext(false).verifyComplete();
        verify(surpriseService, never()).releaseAfterFriendshipEnded(anyString(), anyString());
    }

    @Test
    void applyTombstone_shouldNotEndThePair_whenTheReleaseFails_soTheRetryRunsItAgain() {
        when(directory.trackedPairOf("alice", "bob")).thenReturn(Mono.just(tracked(FRIENDS, 7L)));
        when(surpriseService.releaseAfterFriendshipEnded("alice", "bob"))
                .thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.applyTombstone("alice", "bob")).expectError(IllegalStateException.class).verify();
        verify(directory, never()).end(any());
    }

    @Test
    void recordHistory_shouldAppendTheChange_withoutTouchingTheProjection() {
        nobodyDeleted();
        when(history.append(FriendshipHistoryEntry.of(UPGRADE))).thenReturn(Mono.just(true));

        StepVerifier.create(service.recordHistory(UPGRADE)).expectNext(true).verifyComplete();
        verify(directory, never()).record(any());
        verify(directory, never()).trackedPairOf(anyString(), anyString());
        verify(surpriseService, never()).releaseAfterFriendshipEnded(anyString(), anyString());
    }

    @Test
    void recordHistory_shouldDateAnUndatedChange_withTheCurrentTime() {
        nobodyDeleted();
        FriendshipChange undated = new FriendshipChange(FriendshipChangeType.ACCEPTED, FRIENDS, null, null);
        when(history.append(any())).thenReturn(Mono.just(true));

        StepVerifier.create(service.recordHistory(undated)).expectNext(true).verifyComplete();
        verify(history).append(FriendshipHistoryEntry.of(undated.datedOrAt(NOW)));
    }

    @Test
    void recordHistory_shouldAnswerFalse_whenTheHistoryAlreadyHasTheChange() {
        nobodyDeleted();
        when(history.append(FriendshipHistoryEntry.of(REMOVAL))).thenReturn(Mono.just(false));

        StepVerifier.create(service.recordHistory(REMOVAL)).expectNext(false).verifyComplete();
    }

    @Test
    void recordHistory_shouldIgnoreAChangeNamingADeletedAccount() {
        when(markers.deletedAmong(List.of("alice", "bob"))).thenReturn(Mono.just(Set.of("alice")));

        StepVerifier.create(service.recordHistory(UPGRADE)).expectNext(false).verifyComplete();
        verify(history, never()).append(any());
    }

    @Test
    void viewerFriends_shouldMapEachFriendToTheirCloseness() {
        when(directory.friendsOf("alice")).thenReturn(Flux.just(new Friend("bob", Closeness.CLOSE),
                new Friend("zoe", Closeness.FRIEND)));

        StepVerifier.create(service.viewerFriends("alice"))
                .expectNext(new ViewerFriends(Map.of("bob", Closeness.CLOSE, "zoe", Closeness.FRIEND)))
                .verifyComplete();
    }

    @Test
    void viewerFriends_shouldBeNone_withoutFriends() {
        when(directory.friendsOf("alice")).thenReturn(Flux.empty());

        StepVerifier.create(service.viewerFriends("alice")).expectNext(ViewerFriends.NONE).verifyComplete();
    }

    @Test
    void viewerFriends_shouldPassAReadFailureOn() {
        when(directory.friendsOf("alice")).thenReturn(Flux.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.viewerFriends("alice")).expectError(IllegalStateException.class).verify();
    }

    @Test
    void forget_shouldEraseThePairsTheHistoryTheNoticesAndTheSnoozes_inOrder() {
        when(directory.forget("alice")).thenReturn(Mono.just(2L));
        when(history.forget("alice")).thenReturn(Mono.just(5L));
        when(noticeOutbox.forget("alice")).thenReturn(Mono.empty());
        when(snoozes.forget("alice")).thenReturn(Mono.empty());

        StepVerifier.create(service.forget("alice")).verifyComplete();
        InOrder order = inOrder(directory, history, noticeOutbox, snoozes);
        order.verify(directory).forget("alice");
        order.verify(history).forget("alice");
        order.verify(noticeOutbox).forget("alice");
        order.verify(snoozes).forget("alice");
    }

    @Test
    void forget_shouldStop_whenAStepFails_forTheErasureToBeRetried() {
        when(directory.forget("alice")).thenReturn(Mono.just(2L));
        when(history.forget("alice")).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.forget("alice")).expectError(IllegalStateException.class).verify();
        verify(noticeOutbox, never()).forget(anyString());
        verify(snoozes, never()).forget(anyString());
    }
}
