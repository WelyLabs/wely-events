package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.EventDeletion;
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

/**
 * The deletion outbox against a real MongoDB: tombstones come back oldest first, in batches, and go
 * once marked sent.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoEventDeletionOutboxAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Autowired
    private MongoEventDeletionOutboxAdapter outbox;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearTombstones() {
        mongoTemplate.remove(new Query(), "event_deletions").then().block();
    }

    @Test
    void record_shouldStoreATombstone_thatFindPendingGivesBack() {
        EventDeletion deletion = new EventDeletion("e1", "Quiz", 4L, NOW);

        StepVerifier.create(outbox.record(deletion)).verifyComplete();

        StepVerifier.create(outbox.findPending(10)).expectNext(deletion).verifyComplete();
    }

    @Test
    void findPending_shouldGiveTheOldestFirst_inBatches() {
        outbox.record(new EventDeletion("late", "Late", 1L, NOW.plusSeconds(60))).block();
        outbox.record(new EventDeletion("early", "Early", 1L, NOW)).block();
        outbox.record(new EventDeletion("middle", "Middle", 1L, NOW.plusSeconds(30))).block();

        assertThat(outbox.findPending(2).map(EventDeletion::eventId).collectList().block())
                .containsExactly("early", "middle");
    }

    @Test
    void markPublished_shouldRemoveOnlyThatTombstone() {
        outbox.record(new EventDeletion("e1", "Quiz", 4L, NOW)).block();
        outbox.record(new EventDeletion("e2", "Bowling", 2L, NOW)).block();

        StepVerifier.create(outbox.markPublished("e1")).verifyComplete();

        assertThat(outbox.findPending(10).map(EventDeletion::eventId).collectList().block()).containsExactly("e2");
    }
}
