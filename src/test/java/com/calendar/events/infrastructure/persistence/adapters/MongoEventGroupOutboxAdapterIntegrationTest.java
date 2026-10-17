package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The group outbox against a real MongoDB: the flag and the version are set by the same update as
 * the participant change, and clearing the flag only works on the version that was read. Both are
 * claims about MongoDB's update semantics, which a mock cannot settle.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoEventGroupOutboxAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private MongoEventGroupOutboxAdapter outbox;

    @Autowired
    private MongoEventRepositoryAdapter events;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearEvents() {
        mongoTemplate.remove(new Query(), "events").then().block();
    }

    private String anEvent() {
        return events.save(Event.builder()
                .title("Board games")
                .organizerId("org")
                .startDate(Instant.parse("2026-10-12T17:30:00Z"))
                .location("Le Dé")
                .participantIds(new ArrayList<>())
                .build()).block().getId();
    }

    private EventEntity stored(String id) {
        return mongoTemplate.findById(id, EventEntity.class).block();
    }

    @Test
    void findPending_shouldIncludeANewEvent() {
        String id = anEvent();

        assertThat(outbox.findPending(10).map(EventEntity::getId).collectList().block()).containsExactly(id);
    }

    @Test
    void markPublished_shouldClearANewEventWithoutVersion() {
        String id = anEvent();

        assertThat(outbox.markPublished(id, null).block()).isTrue();
        assertThat(outbox.findPending(10).collectList().block()).isEmpty();
    }

    @Test
    void addParticipant_shouldBumpTheVersionAndFlagTheGroup() {
        String id = anEvent();
        outbox.markPublished(id, null).block();

        events.addParticipant(id, "camille").block();

        assertThat(stored(id).getGroupVersion()).isEqualTo(1L);
        assertThat(stored(id).getGroupSynced()).isFalse();
    }

    @Test
    void addParticipant_shouldNotBumpTheVersion_whenAlreadyTakingPart() {
        String id = anEvent();
        events.addParticipant(id, "camille").block();

        events.addParticipant(id, "camille").block();

        assertThat(stored(id).getGroupVersion()).isEqualTo(1L);
    }

    @Test
    void removeParticipant_shouldBumpTheVersion_onlyForSomeoneTakingPart() {
        String id = anEvent();
        events.addParticipant(id, "camille").block();

        events.removeParticipant(id, "nobody").block();
        events.removeParticipant(id, "camille").block();

        assertThat(stored(id).getGroupVersion()).isEqualTo(2L);
        assertThat(stored(id).getParticipantIds()).isEmpty();
    }

    @Test
    void markPublished_shouldLeaveTheGroupPending_whenItChangedAfterBeingRead() {
        String id = anEvent();
        events.addParticipant(id, "camille").block();
        Long read = stored(id).getGroupVersion();

        events.addParticipant(id, "ines").block();

        assertThat(outbox.markPublished(id, read).block()).isFalse();
        assertThat(outbox.findPending(10).map(EventEntity::getId).collectList().block()).containsExactly(id);
    }

    @Test
    void findPending_shouldLeaveBirthdaysOut_whichHaveNoGroup() {
        events.upsertBirthday(com.calendar.events.domain.services.BirthdayService.birthdayEvent("lea", "Léa",
                java.time.MonthDay.of(3, 12))).block();

        assertThat(outbox.findPending(10).collectList().block()).isEmpty();
    }
}
