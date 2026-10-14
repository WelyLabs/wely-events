package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The date-fixed outbox against a real MongoDB: only a fixed date is pending, never a plain event
 * nor an open poll, and marking it sent works once.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoEventDateFixedOutboxAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final PollSlot THURSDAY = new PollSlot("thu", Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"));
    private static final PollSlot FRIDAY = new PollSlot("fri", Instant.parse("2026-10-17T17:00:00Z"), Instant.parse("2026-10-17T19:00:00Z"));

    @Autowired
    private MongoEventDateFixedOutboxAdapter outbox;

    @Autowired
    private MongoEventRepositoryAdapter events;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearEvents() {
        mongoTemplate.remove(new Query(), "events").then().block();
    }

    private String anEvent(Poll poll) {
        return events.save(Event.builder()
                .title("Dinner")
                .organizerId("org")
                .startDate(THURSDAY.start())
                .location("Lyon")
                .participantIds(new ArrayList<>())
                .poll(poll)
                .build()).block().getId();
    }

    private List<String> pending() {
        return outbox.findPending(10).map(EventEntity::getId).collectList().block();
    }

    @Test
    void findPending_shouldIgnorePlainEventsAndOpenPolls() {
        anEvent(null);
        anEvent(new Poll(PollStatus.OPEN, List.of(THURSDAY, FRIDAY), null, null));

        assertThat(pending()).isEmpty();
    }

    @Test
    void findPending_shouldIncludeAFixedDate_untilMarkedSent() {
        String id = anEvent(new Poll(PollStatus.OPEN, List.of(THURSDAY, FRIDAY), null, null));
        events.fixPollDate(id, "org", FRIDAY, List.of()).block();

        assertThat(pending()).containsExactly(id);
        assertThat(outbox.markPublished(id).block()).isTrue();
        assertThat(pending()).isEmpty();
    }

    @Test
    void markPublished_shouldWorkOnce() {
        String id = anEvent(new Poll(PollStatus.OPEN, List.of(THURSDAY, FRIDAY), null, null));
        events.fixPollDate(id, "org", FRIDAY, List.of()).block();
        outbox.markPublished(id).block();

        assertThat(outbox.markPublished(id).block()).isFalse();
    }
}
