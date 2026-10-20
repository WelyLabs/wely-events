package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.TrackedPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoFriendshipDirectoryAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoFriendshipDirectoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");

    @Autowired
    private MongoFriendshipDirectoryAdapter adapter;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearPairs() {
        mongoTemplate.remove(new Query(), "friendships").then().block();
    }

    @Test
    void record_shouldInsertThenOverwriteWithANewerVersion() {
        StepVerifier.create(adapter.record(new Friendship("bob", "alice", true, Closeness.FRIEND, SINCE, 1)))
                .expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.record(new Friendship("alice", "bob", true, Closeness.INTIMATE, SINCE, 2)))
                .expectNext(true).expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(adapter.friendsOf("alice")).expectNext(new Friend("bob", Closeness.INTIMATE)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldIgnoreAStaleOrRepeatedVersion() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 5)).block();

        StepVerifier.create(adapter.record(new Friendship("alice", "bob", false, null, null, 4)))
                .expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.record(new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 5)))
                .expectNext(false).expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(adapter.friendsOf("bob")).expectNext(new Friend("alice", Closeness.CLOSE)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldKeepARemovalAsATombstone_thatAnOlderAcceptanceCannotUndo() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 1)).block();
        adapter.record(new Friendship("alice", "bob", false, null, null, 3)).block();

        StepVerifier.create(adapter.record(new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 2)))
                .expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.friendsOf("alice")).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(mongoTemplate.count(new Query(), "friendships").block()).isEqualTo(1L);
    }

    @Test
    void friendsOf_shouldFindTheUserOnEitherSide_amongFriendsOnly() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 1)).block();
        adapter.record(new Friendship("bob", "zoe", true, Closeness.FRIEND, SINCE, 1)).block();
        adapter.record(new Friendship("bob", "max", false, null, null, 1)).block();
        adapter.record(new Friendship("alice", "zoe", true, Closeness.FRIEND, SINCE, 1)).block();

        assertThat(adapter.friendsOf("bob").collectList().block())
                .containsExactlyInAnyOrder(new Friend("alice", Closeness.CLOSE), new Friend("zoe", Closeness.FRIEND));
    }

    @Test
    void forget_shouldDropEveryPairInvolvingTheUser_tombstonesIncluded() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 1)).block();
        adapter.record(new Friendship("bob", "zoe", false, null, null, 1)).block();
        adapter.record(new Friendship("alice", "zoe", true, Closeness.FRIEND, SINCE, 1)).block();

        StepVerifier.create(adapter.forget("bob")).expectNext(2L).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.friendsOf("alice")).expectNext(new Friend("zoe", Closeness.FRIEND)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void friendshipsOf_shouldReadTheUsersPairsStillFriends_withTheirStart() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 1)).block();
        adapter.record(new Friendship("bob", "max", false, null, null, 1)).block();

        StepVerifier.create(adapter.friendshipsOf("bob"))
                .expectNext(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 1))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void allFriendships_shouldSkipTombstones() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 1)).block();
        adapter.record(new Friendship("bob", "max", false, null, null, 1)).block();
        adapter.record(new Friendship("max", "zoe", true, Closeness.FRIEND, SINCE, 2)).block();

        assertThat(adapter.allFriendships().collectList().block())
                .extracting(Friendship::pairId).containsExactlyInAnyOrder("alice:bob", "max:zoe");
    }

    @Test
    void pairOf_shouldReadAPair_friendsOrNot() {
        adapter.record(new Friendship("alice", "bob", false, null, null, 4)).block();

        StepVerifier.create(adapter.pairOf("bob", "alice"))
                .expectNext(new Friendship("alice", "bob", false, null, null, 4)).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.pairOf("bob", "zoe")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void trackedPairOf_shouldReadTheLastStreamedVersion_writtenWithThePairOrMarkedLater() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 5)).block();

        StepVerifier.create(adapter.trackedPairOf("bob", "alice"))
                .expectNext(new TrackedPair(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 5), 5L))
                .expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(adapter.markStreamed("bob", "alice", 3)).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.trackedPairOf("alice", "bob"))
                .expectNext(new TrackedPair(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 5), 3L))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markStreamed_shouldNotCreateAnUnknownPair() {
        StepVerifier.create(adapter.markStreamed("alice", "bob", 3)).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(mongoTemplate.count(new Query(), "friendships").block()).isZero();
    }

    @Test
    void end_shouldEndThePairOnce_keepingItsVersion() {
        Friendship close = new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 5);
        adapter.record(close).block();

        StepVerifier.create(adapter.end(close)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.end(close)).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(adapter.pairOf("alice", "bob"))
                .expectNext(new Friendship("alice", "bob", false, null, null, 5)).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.record(new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 6)))
                .expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void end_shouldLeaveAPairThatMovedOn() {
        adapter.record(new Friendship("alice", "bob", true, Closeness.INTIMATE, SINCE, 6)).block();

        StepVerifier.create(adapter.end(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 5)))
                .expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.friendsOf("alice")).expectNext(new Friend("bob", Closeness.INTIMATE)).expectComplete().verify(Duration.ofSeconds(10));
    }
}
