package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
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
import java.util.Map;
import java.util.Set;

/** Runs {@link MongoSocialGraphAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoSocialGraphAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");

    @Autowired
    private MongoSocialGraphAdapter adapter;

    @Autowired
    private MongoFriendshipDirectoryAdapter directory;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void seedPairs() {
        mongoTemplate.remove(new Query(), "friendships").then().block();
        directory.record(new Friendship("alice", "bob", true, Closeness.INTIMATE, SINCE, 1)).block();
        directory.record(new Friendship("bob", "zoe", true, Closeness.FRIEND, SINCE, 1)).block();
        directory.record(new Friendship("bob", "max", false, null, null, 2)).block();
    }

    @Test
    void friendIdsOf_shouldListCurrentFriendsOnly() {
        StepVerifier.create(adapter.friendIdsOf("bob")).expectNext(Set.of("alice", "zoe")).verifyComplete();
        StepVerifier.create(adapter.friendIdsOf("nobody")).expectNext(Set.of()).verifyComplete();
    }

    @Test
    void closenessBetween_shouldAnswerForFriendsOnly() {
        StepVerifier.create(adapter.closenessBetween("bob", "alice")).expectNext(Closeness.INTIMATE).verifyComplete();
        StepVerifier.create(adapter.closenessBetween("max", "bob")).verifyComplete();
        StepVerifier.create(adapter.closenessBetween("alice", "zoe")).verifyComplete();
    }

    @Test
    void friendsOf_shouldMapFriendsToTheirCloseness() {
        StepVerifier.create(adapter.friendsOf("bob"))
                .expectNext(Map.of("alice", Closeness.INTIMATE, "zoe", Closeness.FRIEND))
                .verifyComplete();
    }
}
