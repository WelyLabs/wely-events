package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.TrackedPair;
import com.calendar.events.infrastructure.persistence.entities.FriendshipEntity;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoFriendshipDirectoryAdapterTest {

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");
    private static final Friendship CLOSE = new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 7);

    @Mock
    private ReactiveFriendshipMongoRepository mongoRepository;

    private final FriendshipPersistenceMapper mapper = Mappers.getMapper(FriendshipPersistenceMapper.class);
    private MongoFriendshipDirectoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoFriendshipDirectoryAdapter(mongoRepository, mapper);
    }

    @Test
    void friendsOf_shouldNameTheOtherSideOfEachPair() {
        when(mongoRepository.findFriendshipsOf("bob")).thenReturn(Flux.just(
                mapper.toEntity(CLOSE),
                mapper.toEntity(new Friendship("bob", "zoe", true, Closeness.FRIEND, SINCE, 2))));

        StepVerifier.create(adapter.friendsOf("bob"))
                .expectNext(new Friend("alice", Closeness.CLOSE), new Friend("zoe", Closeness.FRIEND))
                .verifyComplete();
    }

    @Test
    void friendsOf_shouldPassAReadFailureOn() {
        when(mongoRepository.findFriendshipsOf("bob")).thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.friendsOf("bob")).expectError(DataAccessResourceFailureException.class).verify();
    }

    @Test
    void friendshipsOf_shouldReadThePairsWithTheirStart() {
        when(mongoRepository.findFriendshipsOf("bob")).thenReturn(Flux.just(mapper.toEntity(CLOSE)));

        StepVerifier.create(adapter.friendshipsOf("bob")).expectNext(CLOSE).verifyComplete();
    }

    @Test
    void allFriendships_shouldReadEveryPairStillFriends() {
        when(mongoRepository.findAllFriends()).thenReturn(Flux.just(mapper.toEntity(CLOSE)));

        StepVerifier.create(adapter.allFriendships()).expectNext(CLOSE).verifyComplete();
    }

    @Test
    void pairOf_shouldReadThePairInEitherOrder_tombstoneIncluded() {
        Friendship parted = new Friendship("alice", "bob", false, null, null, 9);
        when(mongoRepository.findById("alice:bob")).thenReturn(Mono.just(mapper.toEntity(parted)));

        StepVerifier.create(adapter.pairOf("bob", "alice")).expectNext(parted).verifyComplete();
    }

    @Test
    void pairOf_shouldBeEmptyForOneself_withoutReading() {
        StepVerifier.create(adapter.pairOf("bob", "bob")).verifyComplete();

        verify(mongoRepository, never()).findById(any(String.class));
    }

    @Test
    void record_shouldOverwriteAnOlderPair_withoutInserting() {
        when(mongoRepository.overwriteIfOlder("alice:bob", true, "CLOSE", SINCE, 7L)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.record(CLOSE)).expectNext(true).verifyComplete();
        verify(mongoRepository, never()).insert(any(FriendshipEntity.class));
    }

    @Test
    void record_shouldInsertANewPair() {
        when(mongoRepository.overwriteIfOlder("alice:bob", true, "CLOSE", SINCE, 7L)).thenReturn(Mono.just(0L));
        when(mongoRepository.insert(argThat((FriendshipEntity entity) -> "alice:bob".equals(entity.getId())
                && "alice".equals(entity.getA()) && "bob".equals(entity.getB()) && entity.getVersion() == 7
                && Long.valueOf(7).equals(entity.getStreamedVersion()))))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(adapter.record(CLOSE)).expectNext(true).verifyComplete();
    }

    @Test
    void record_shouldAnswerFalse_whenTheStoredPairIsAsNewOrNewer() {
        when(mongoRepository.overwriteIfOlder("alice:bob", true, "CLOSE", SINCE, 7L)).thenReturn(Mono.just(0L));
        when(mongoRepository.insert(any(FriendshipEntity.class))).thenReturn(Mono.error(new DuplicateKeyException("E11000")));

        StepVerifier.create(adapter.record(CLOSE)).expectNext(false).verifyComplete();
        // The second overwrite settles a pair inserted meanwhile with an older version.
        verify(mongoRepository, times(2)).overwriteIfOlder("alice:bob", true, "CLOSE", SINCE, 7L);
    }

    @Test
    void record_shouldWriteATombstoneWithoutCloseness() {
        when(mongoRepository.overwriteIfOlder("alice:bob", false, null, null, 8L)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.record(new Friendship("bob", "alice", false, null, null, 8)))
                .expectNext(true).verifyComplete();
    }

    @Test
    void record_shouldPassAWriteFailureOn() {
        when(mongoRepository.overwriteIfOlder("alice:bob", true, "CLOSE", SINCE, 7L))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.record(CLOSE)).expectError(DataAccessResourceFailureException.class).verify();
    }

    @Test
    void trackedPairOf_shouldReadThePairWithItsLastStreamedVersion() {
        FriendshipEntity entity = mapper.toEntity(CLOSE);
        entity.setStreamedVersion(5L);
        when(mongoRepository.findById("alice:bob")).thenReturn(Mono.just(entity));

        StepVerifier.create(adapter.trackedPairOf("bob", "alice")).expectNext(new TrackedPair(CLOSE, 5L)).verifyComplete();
    }

    @Test
    void trackedPairOf_shouldBeEmptyForOneself_withoutReading() {
        StepVerifier.create(adapter.trackedPairOf("bob", "bob")).verifyComplete();

        verify(mongoRepository, never()).findById(any(String.class));
    }

    @Test
    void markStreamed_shouldNoteTheVersionOnThePair() {
        when(mongoRepository.markStreamed("alice:bob", 4L)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.markStreamed("bob", "alice", 4)).verifyComplete();
        verify(mongoRepository).markStreamed("alice:bob", 4L);
    }

    @Test
    void end_shouldEndThePairAtItsVersion() {
        when(mongoRepository.endIfAt("alice:bob", 7L)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.end(CLOSE)).expectNext(true).verifyComplete();
    }

    @Test
    void end_shouldAnswerFalse_whenThePairChangedMeanwhile() {
        when(mongoRepository.endIfAt("alice:bob", 7L)).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.end(CLOSE)).expectNext(false).verifyComplete();
    }

    @Test
    void forget_shouldDeleteEveryPairInvolvingTheUser() {
        when(mongoRepository.deleteInvolving("bob")).thenReturn(Mono.just(3L));

        StepVerifier.create(adapter.forget("bob")).expectNext(3L).verifyComplete();
    }
}
