package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendList;
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

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoFriendListsAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoFriendListsAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant NOW = Instant.parse("2027-02-19T07:00:00Z");

    @Autowired
    private MongoFriendListsAdapter adapter;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearLists() {
        mongoTemplate.remove(new Query(), "friend_lists").then().block();
    }

    @Test
    void save_shouldReplaceTheUsersList() {
        adapter.save(new FriendList("lea", Map.of("bob", Closeness.FRIEND), NOW)).block();
        FriendList newer = new FriendList("lea", Map.of("zoe", Closeness.CLOSE), NOW.plusSeconds(60));
        adapter.save(newer).block();

        StepVerifier.create(adapter.findOf("lea")).expectNext(newer).verifyComplete();
    }

    @Test
    void findNaming_shouldFindEveryOtherListWithTheUserAmongTheFriends() {
        adapter.save(new FriendList("bob", Map.of("lea", Closeness.CLOSE, "zoe", Closeness.FRIEND), NOW)).block();
        adapter.save(new FriendList("zoe", Map.of("bob", Closeness.FRIEND), NOW)).block();
        adapter.save(new FriendList("lea", Map.of("bob", Closeness.CLOSE), NOW)).block();

        assertThat(adapter.findNaming("lea").map(FriendList::userId).collectList().block()).containsExactly("bob");
    }

    @Test
    void delete_shouldDropTheUsersList() {
        adapter.save(new FriendList("lea", Map.of(), NOW)).block();

        adapter.delete("lea").block();

        StepVerifier.create(adapter.findOf("lea")).verifyComplete();
    }
}
