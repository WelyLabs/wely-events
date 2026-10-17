package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.infrastructure.persistence.entities.CalendarLinkEntity;
import org.bson.Document;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoCalendarLinksAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoCalendarLinksAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final Instant EXPIRY = Instant.parse("2026-10-17T10:05:00Z");

    @Autowired
    private MongoCalendarLinksAdapter adapter;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mappingContext;

    @BeforeEach
    void clearLinks() {
        mongoTemplate.remove(new Query(), "calendar_links").then().block();
    }

    @Test
    void save_thenFindByToken_shouldGiveTheLinkBack() {
        CalendarLink link = new CalendarLink("tok", "e1", "https://web.welylabs.app/event/e1", EXPIRY);

        adapter.save(link).block();

        StepVerifier.create(adapter.findByToken("tok")).expectNext(link).verifyComplete();
        StepVerifier.create(adapter.findByToken("other")).verifyComplete();
    }

    @Test
    void calendarLinks_shouldExpireThroughATtlIndexOnExpiresAt() {
        // The test profile turns auto-index-creation off: the indexes the entity declares are
        // created here as the application creates them at startup.
        IndexResolver.create(mappingContext).resolveIndexFor(CalendarLinkEntity.class)
                .forEach(index -> mongoTemplate.indexOps(CalendarLinkEntity.class).createIndex(index).block());

        Document ttl = mongoTemplate.getCollection("calendar_links")
                .flatMapMany(collection -> collection.listIndexes())
                .filter(index -> index.containsKey("expireAfterSeconds"))
                .blockFirst();

        assertThat(ttl).isNotNull();
        assertThat(ttl.get("key", Document.class)).containsKey("expiresAt");
        assertThat(ttl.get("expireAfterSeconds", Number.class).longValue()).isZero();
    }
}
