package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.infrastructure.persistence.entities.ReunionSnoozeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoReunionSnoozesAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoReunionSnoozesAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @Autowired
    private MongoReunionSnoozesAdapter snoozes;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mappingContext;

    @BeforeEach
    void clearSnoozes() {
        mongoTemplate.remove(new Query(), "reunion_snoozes").then().block();
    }

    @Test
    void snoozedBy_shouldListTheUsersRunningSnoozes_inOneDirection() {
        snoozes.snooze("me", "hugo", NOW.plus(Duration.ofDays(30))).block();
        snoozes.snooze("me", "lea", NOW.minusSeconds(1)).block();
        snoozes.snooze("hugo", "me", NOW.plus(Duration.ofDays(30))).block();

        StepVerifier.create(snoozes.snoozedBy("me", NOW)).expectNext(Set.of("hugo")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void snooze_shouldReplaceTheEarlierSnoozeOfThePair() {
        snoozes.snooze("me", "hugo", NOW.minusSeconds(60)).block();
        snoozes.snooze("me", "hugo", NOW.plus(Duration.ofDays(30))).block();

        StepVerifier.create(snoozes.snoozedBy("me", NOW)).expectNext(Set.of("hugo")).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(mongoTemplate.count(new Query(), "reunion_snoozes").block()).isEqualTo(1L);
    }

    @Test
    void forget_shouldDropEverySnoozeByOrAboutTheUser() {
        snoozes.snooze("me", "hugo", NOW.plus(Duration.ofDays(30))).block();
        snoozes.snooze("hugo", "me", NOW.plus(Duration.ofDays(30))).block();
        snoozes.snooze("lea", "max", NOW.plus(Duration.ofDays(30))).block();

        snoozes.forget("hugo").block();

        assertThat(mongoTemplate.count(new Query(), "reunion_snoozes").block()).isEqualTo(1L);
    }

    @Test
    void reunionSnoozes_shouldExpireWhenTheirSnoozeEnds() {
        IndexResolver.create(mappingContext).resolveIndexFor(ReunionSnoozeEntity.class)
                .forEach(index -> mongoTemplate.indexOps(ReunionSnoozeEntity.class).createIndex(index).block());

        assertThat(mongoTemplate.getCollection("reunion_snoozes")
                .flatMapMany(collection -> collection.listIndexes())
                .filter(index -> index.containsKey("expireAfterSeconds"))
                .map(index -> ((Number) index.get("expireAfterSeconds")).longValue())
                .collectList().block())
                .containsExactly(0L);
    }
}
