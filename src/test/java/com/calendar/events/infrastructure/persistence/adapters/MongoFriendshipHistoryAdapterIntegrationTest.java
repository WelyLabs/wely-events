package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendshipChangeType;
import com.calendar.events.domain.models.FriendshipHistoryEntry;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoFriendshipHistoryAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoFriendshipHistoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant JAN = Instant.parse("2026-01-10T10:00:00Z");
    private static final Instant MAR = Instant.parse("2026-03-10T10:00:00Z");
    private static final Instant DEC_LAST_YEAR = Instant.parse("2025-12-20T10:00:00Z");

    @Autowired
    private MongoFriendshipHistoryAdapter adapter;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearHistory() {
        mongoTemplate.remove(new Query(), "friendship_history").then().block();
    }

    private static FriendshipHistoryEntry entry(String a, String b, FriendshipChangeType type, Closeness closeness,
                                                Instant at, long version) {
        return new FriendshipHistoryEntry(a + ":" + b, a, b, type, closeness, null, at, version);
    }

    @Test
    void append_shouldKeepOneEntryPerPairAndVersion() {
        FriendshipHistoryEntry accepted = entry("alice", "bob", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, JAN, 1);

        StepVerifier.create(adapter.append(accepted)).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.append(accepted)).expectNext(false).verifyComplete();
        StepVerifier.create(adapter.append(entry("alice", "bob", FriendshipChangeType.CLOSENESS_CHANGED, Closeness.CLOSE,
                MAR, 2))).expectNext(true).verifyComplete();

        assertThat(mongoTemplate.count(new Query(), "friendship_history").block()).isEqualTo(2L);
    }

    @Test
    void involvingSince_shouldFindTheUserOnEitherSide_fromTheDate_oldestFirst() {
        adapter.append(entry("alice", "bob", FriendshipChangeType.CLOSENESS_CHANGED, Closeness.CLOSE, MAR, 2)).block();
        adapter.append(entry("alice", "bob", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, JAN, 1)).block();
        adapter.append(entry("bob", "zoe", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, MAR.plusSeconds(60), 1)).block();
        adapter.append(entry("alice", "max", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, DEC_LAST_YEAR, 1)).block();
        adapter.append(entry("max", "zoe", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, MAR, 1)).block();

        assertThat(adapter.involvingSince("bob", JAN).collectList().block())
                .extracting(FriendshipHistoryEntry::pairId, FriendshipHistoryEntry::version)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("alice:bob", 1L),
                        org.assertj.core.groups.Tuple.tuple("alice:bob", 2L),
                        org.assertj.core.groups.Tuple.tuple("bob:zoe", 1L));
        assertThat(adapter.involvingSince("alice", JAN).collectList().block()).hasSize(2);
    }

    @Test
    void forget_shouldDropEveryEntryInvolvingTheUser() {
        adapter.append(entry("alice", "bob", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, JAN, 1)).block();
        adapter.append(entry("bob", "zoe", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, JAN, 1)).block();
        adapter.append(entry("alice", "zoe", FriendshipChangeType.ACCEPTED, Closeness.FRIEND, JAN, 1)).block();

        StepVerifier.create(adapter.forget("bob")).expectNext(2L).verifyComplete();
        assertThat(adapter.involvingSince("alice", DEC_LAST_YEAR).collectList().block())
                .extracting(FriendshipHistoryEntry::pairId).containsExactly("alice:zoe");
    }
}
