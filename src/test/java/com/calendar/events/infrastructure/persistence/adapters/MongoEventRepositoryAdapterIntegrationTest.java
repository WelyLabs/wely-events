package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link MongoEventRepositoryAdapter} against a real MongoDB.
 *
 * <p>The subscription methods are the reason this exists. {@code addParticipant} uses
 * {@code $addToSet} and {@code removeParticipant} uses {@code $pull}, both through
 * {@code findAndModify} with {@code returnNew} — chosen so that two people subscribing at the
 * same time cannot overwrite each other, which is exactly what a read-modify-write would do.
 * That property is a claim about MongoDB, and only MongoDB can settle it.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoEventRepositoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private MongoEventRepositoryAdapter adapter;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    private static final String ORGANIZER = "organizer-1";

    @BeforeEach
    void clearEvents() {
        // Documents, not the collection: dropping takes the indexes with it and Spring Data
        // recreates them only once, lazily.
        mongoTemplate.remove(new Query(), "events").then().block();
    }

    private Event anEvent(String title) {
        return adapter.save(Event.builder()
                .title(title)
                .organizerId(ORGANIZER)
                .startDate(Instant.parse("2025-06-01T10:00:00Z"))
                .endDate(Instant.parse("2025-06-01T12:00:00Z"))
                .location("Room 1")
                .description("...")
                .participantIds(new ArrayList<>())
                .build()).block();
    }

    @Test
    @DisplayName("a saved event comes back with an id and every field")
    void save_shouldRoundTripTheEvent() {
        Event saved = anEvent("Standup");

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();

        StepVerifier.create(adapter.findById(saved.getId()))
                .assertNext(found -> {
                    assertThat(found.getTitle()).isEqualTo("Standup");
                    assertThat(found.getOrganizerId()).isEqualTo(ORGANIZER);
                    assertThat(found.getStartDate()).isEqualTo(Instant.parse("2025-06-01T10:00:00Z"));
                    assertThat(found.getLocation()).isEqualTo("Room 1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an unknown id yields nothing rather than a blank event")
    void findById_shouldBeEmptyForAnUnknownId() {
        StepVerifier.create(adapter.findById("64b7f9f2e4b0a1c2d3e4f5a6")).verifyComplete();
    }

    @Test
    @DisplayName("subscribing adds the user to the event, and returns the updated document")
    void addParticipant_shouldReturnTheEventAfterTheUpdate() {
        // returnNew(true) is the difference between showing the caller their subscription and
        // showing them the state before it.
        Event saved = anEvent("Standup");

        StepVerifier.create(adapter.addParticipant(saved.getId(), "alice"))
                .assertNext(updated -> assertThat(updated.getParticipantIds()).containsExactly("alice"))
                .verifyComplete();
    }

    @Test
    @DisplayName("subscribing twice leaves one entry")
    void addParticipant_shouldBeIdempotent() {
        // $addToSet, not $push. A retried request must not list the same person twice.
        Event saved = anEvent("Standup");

        adapter.addParticipant(saved.getId(), "alice").block();
        adapter.addParticipant(saved.getId(), "alice").block();

        StepVerifier.create(adapter.findById(saved.getId()))
                .assertNext(found -> assertThat(found.getParticipantIds()).containsExactly("alice"))
                .verifyComplete();
    }

    @Test
    @DisplayName("concurrent subscriptions all survive")
    void addParticipant_shouldNotLoseConcurrentSubscriptions() {
        // The reason for $addToSet over a read-modify-write: twenty people subscribing at once
        // would otherwise overwrite each other, and nothing but a real database shows it.
        Event saved = anEvent("Conference");

        StepVerifier.create(
                        Flux.range(1, 20)
                                .flatMap(i -> adapter.addParticipant(saved.getId(), "user-" + i), 10))
                .expectNextCount(20)
                .verifyComplete();

        StepVerifier.create(adapter.findById(saved.getId()))
                .assertNext(found -> assertThat(found.getParticipantIds()).hasSize(20))
                .verifyComplete();
    }

    @Test
    @DisplayName("unsubscribing removes only that user")
    void removeParticipant_shouldPullOnlyTheGivenUser() {
        Event saved = anEvent("Standup");
        adapter.addParticipant(saved.getId(), "alice").block();
        adapter.addParticipant(saved.getId(), "bob").block();

        StepVerifier.create(adapter.removeParticipant(saved.getId(), "alice"))
                .assertNext(updated -> assertThat(updated.getParticipantIds()).containsExactly("bob"))
                .verifyComplete();
    }

    @Test
    @DisplayName("unsubscribing someone who never subscribed changes nothing")
    void removeParticipant_shouldTolerateANonParticipant() {
        Event saved = anEvent("Standup");
        adapter.addParticipant(saved.getId(), "alice").block();

        StepVerifier.create(adapter.removeParticipant(saved.getId(), "bob"))
                .assertNext(updated -> assertThat(updated.getParticipantIds()).containsExactly("alice"))
                .verifyComplete();
    }

    @Test
    @DisplayName("touching a missing event yields nothing rather than creating one")
    void addParticipant_shouldNotUpsert() {
        // findAndModify without upsert: subscribing to an event that does not exist must not
        // bring it into being.
        StepVerifier.create(adapter.addParticipant("64b7f9f2e4b0a1c2d3e4f5a6", "alice"))
                .verifyComplete();

        StepVerifier.create(adapter.findAll()).verifyComplete();
    }

    @Test
    @DisplayName("the feed splits on whether the caller is a participant")
    void findByParticipantId_shouldPartitionTheEvents() {
        Event subscribed = anEvent("Subscribed");
        Event other = anEvent("Not subscribed");
        adapter.addParticipant(subscribed.getId(), "alice").block();

        StepVerifier.create(adapter.findByParticipantId("alice"))
                .assertNext(event -> assertThat(event.getId()).isEqualTo(subscribed.getId()))
                .verifyComplete();

        StepVerifier.create(adapter.findByParticipantIdNot("alice"))
                .assertNext(event -> assertThat(event.getId()).isEqualTo(other.getId()))
                .verifyComplete();
    }

    @Test
    @DisplayName("an event with no participants counts as not subscribed")
    void findByParticipantIdNot_shouldIncludeEmptyEvents() {
        // $ne on an array is true when the array is missing the value, empty included — worth
        // pinning, because the alternative reading would hide every new event from the feed.
        anEvent("Nobody yet");

        StepVerifier.create(adapter.findByParticipantIdNot("alice")).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("deleting removes the event")
    void deleteById_shouldRemoveTheEvent() {
        Event saved = anEvent("Standup");

        StepVerifier.create(adapter.deleteById(saved.getId())).verifyComplete();
        StepVerifier.create(adapter.findById(saved.getId())).verifyComplete();
    }

    @Test
    @DisplayName("findAll returns what was stored")
    void findAll_shouldReturnEveryEvent() {
        anEvent("One");
        anEvent("Two");

        List<Event> all = adapter.findAll().collectList().block();

        assertThat(all).hasSize(2).extracting(Event::getTitle).containsExactlyInAnyOrder("One", "Two");
    }
}
