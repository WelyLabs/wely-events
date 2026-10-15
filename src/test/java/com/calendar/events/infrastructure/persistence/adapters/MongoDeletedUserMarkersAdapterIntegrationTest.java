package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.DeletedUserMarker;
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
import java.util.List;
import java.util.Set;

/** Runs {@link MongoDeletedUserMarkersAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoDeletedUserMarkersAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant FIRST = Instant.parse("2026-10-15T14:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-22T09:00:00Z");

    @Autowired
    private MongoDeletedUserMarkersAdapter adapter;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearMarkers() {
        mongoTemplate.remove(new Query(), "deleted_users").then().block();
    }

    @Test
    void markDeleted_shouldKeepTheFirstDeletionTime_whenRecordedTwice() {
        StepVerifier.create(adapter.markDeleted("u1", FIRST))
                .expectNext(new DeletedUserMarker("u1", FIRST, null))
                .verifyComplete();
        StepVerifier.create(adapter.markDeleted("u1", LATER))
                .expectNext(new DeletedUserMarker("u1", FIRST, null))
                .verifyComplete();
    }

    @Test
    void markErased_shouldKeepTheLatestErasureTime() {
        adapter.markDeleted("u1", FIRST).block();

        adapter.markErased("u1", LATER).block();
        adapter.markErased("u1", FIRST).block();

        StepVerifier.create(adapter.markDeleted("u1", LATER))
                .expectNext(new DeletedUserMarker("u1", FIRST, LATER))
                .verifyComplete();
    }

    @Test
    void markErased_shouldCreateNothing_withoutAMarker() {
        adapter.markErased("u1", LATER).block();

        StepVerifier.create(adapter.isDeleted("u1")).expectNext(false).verifyComplete();
    }

    @Test
    void deletedAmong_shouldFindMarkersByIdInOneQuery() {
        adapter.markDeleted("u1", FIRST).block();
        adapter.markDeleted("u3", FIRST).block();

        StepVerifier.create(adapter.deletedAmong(List.of("u1", "u2", "u3")))
                .expectNext(Set.of("u1", "u3"))
                .verifyComplete();
    }

    @Test
    void isDeleted_shouldAnswerFromTheMarker() {
        adapter.markDeleted("u1", FIRST).block();

        StepVerifier.create(adapter.isDeleted("u1")).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.isDeleted("u2")).expectNext(false).verifyComplete();
    }
}
