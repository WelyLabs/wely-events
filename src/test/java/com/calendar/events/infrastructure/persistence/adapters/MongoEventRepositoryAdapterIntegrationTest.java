package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.Visibility;
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
 * {@code $addToSet} and {@code removeParticipant} uses {@code $pull}, both as in-place updates
 * declared on the repository — chosen so that two people subscribing at the same time cannot
 * overwrite each other, which is exactly what a read-modify-write would do.
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
                .coordinates(new GeoPoint(45.764, 4.8357))
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
                    assertThat(found.getCoordinates()).isEqualTo(new GeoPoint(45.764, 4.8357));
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
        // An update without upsert: subscribing to an event that does not exist must not
        // bring it into being.
        StepVerifier.create(adapter.addParticipant("64b7f9f2e4b0a1c2d3e4f5a6", "alice"))
                .verifyComplete();

        StepVerifier.create(adapter.findAll()).verifyComplete();
    }

    @Test
    @DisplayName("the subscribed list holds only the events the caller takes part in")
    void findByParticipantId_shouldKeepJoinedEventsOnly() {
        Event subscribed = anEvent("Subscribed");
        anEvent("Not subscribed");
        adapter.addParticipant(subscribed.getId(), "alice").block();

        StepVerifier.create(adapter.findByParticipantId("alice"))
                .assertNext(event -> assertThat(event.getId()).isEqualTo(subscribed.getId()))
                .verifyComplete();
    }

    private void visible(String title, String organizer, Visibility visibility, List<String> participants) {
        adapter.save(Event.builder()
                .title(title)
                .organizerId(organizer)
                .startDate(Instant.parse("2026-10-17T10:00:00Z"))
                .location("Lyon")
                .participantIds(new ArrayList<>(participants))
                .visibility(visibility)
                .build()).block();
    }

    private List<String> feedTitles(String userId, List<String> friendIds) {
        return adapter.findFeed(userId, friendIds).map(Event::getTitle).collectList().block();
    }

    @Test
    @DisplayName("the feed shows my events, public ones and my friends' shared ones, never what I joined")
    void findFeed_shouldApplyTheVisibility() {
        visible("mine, not joined", "alice", Visibility.FRIENDS, List.of());
        visible("public, by a stranger", "carol", Visibility.PUBLIC, List.of());
        visible("shared by a friend", "bob", Visibility.FRIENDS, List.of());
        visible("before visibility existed, by a friend", "bob", null, List.of());
        visible("shared by a stranger", "carol", Visibility.FRIENDS, List.of());
        visible("before visibility existed, by a stranger", "carol", null, List.of());
        visible("private, by a friend", "bob", Visibility.PRIVATE, List.of());
        visible("public, already joined", "carol", Visibility.PUBLIC, List.of("alice"));

        assertThat(feedTitles("alice", List.of("bob"))).containsExactlyInAnyOrder(
                "mine, not joined",
                "public, by a stranger",
                "shared by a friend",
                "before visibility existed, by a friend");
    }

    @Test
    @DisplayName("without friends, the feed keeps public events and my own")
    void findFeed_shouldKeepPublicAndOwnEvents_withoutFriends() {
        visible("mine", "alice", Visibility.PRIVATE, List.of());
        visible("public", "carol", Visibility.PUBLIC, List.of());
        visible("shared by bob", "bob", Visibility.FRIENDS, List.of());

        assertThat(feedTitles("alice", List.of())).containsExactlyInAnyOrder("mine", "public");
    }

    @Test
    @DisplayName("an event with no participants counts as not joined")
    void findFeed_shouldIncludeEventsWithoutParticipants() {
        // $ne on an array is true when the array is missing the value, empty included — worth
        // pinning, because the alternative reading would hide every new event from the feed.
        visible("Nobody yet", "carol", Visibility.PUBLIC, List.of());

        assertThat(feedTitles("alice", List.of())).containsExactly("Nobody yet");
    }

    @Test
    @DisplayName("visibility round-trips, and an event saved without one stores none")
    void save_shouldStoreTheVisibility() {
        visible("private", "bob", Visibility.PRIVATE, List.of());
        visible("legacy", "bob", null, List.of());

        assertThat(adapter.findAll().collectMap(Event::getTitle, e -> String.valueOf(e.getVisibility())).block())
                .containsEntry("private", "PRIVATE")
                .containsEntry("legacy", "null");
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

    private Event saveAt(String title, String organizer, List<String> participants, String start, String end) {
        return adapter.save(Event.builder()
                .title(title)
                .organizerId(organizer)
                .startDate(Instant.parse(start))
                .endDate(end == null ? null : Instant.parse(end))
                .location("Somewhere")
                .participantIds(new ArrayList<>(participants))
                .build()).block();
    }

    private List<String> occupyingTitles(List<String> userIds, String from, String to) {
        return adapter.findOccupying(userIds, Instant.parse(from), Instant.parse(to))
                .map(Event::getTitle).collectList().block();
    }

    @Test
    @DisplayName("events a user takes part in or organizes keep them busy; other people's do not")
    void findOccupying_shouldMatchParticipantsAndOrganizers() {
        saveAt("lea joins", "someone", List.of("lea"), "2026-10-17T10:00:00Z", "2026-10-17T11:00:00Z");
        saveAt("hugo organizes", "hugo", List.of(), "2026-10-17T12:00:00Z", "2026-10-17T13:00:00Z");
        saveAt("strangers", "x", List.of("y"), "2026-10-17T14:00:00Z", "2026-10-17T15:00:00Z");

        assertThat(occupyingTitles(List.of("lea", "hugo"), "2026-10-17T00:00:00Z", "2026-10-18T00:00:00Z"))
                .containsExactlyInAnyOrder("lea joins", "hugo organizes");
    }

    @Test
    @DisplayName("only events overlapping the window count; touching its edges is not overlapping")
    void findOccupying_shouldKeepOverlappingEventsOnly() {
        saveAt("before", "lea", List.of(), "2026-10-17T08:00:00Z", "2026-10-17T10:00:00Z");
        saveAt("across the start", "lea", List.of(), "2026-10-17T09:00:00Z", "2026-10-17T11:00:00Z");
        saveAt("inside", "lea", List.of(), "2026-10-17T12:00:00Z", "2026-10-17T13:00:00Z");
        saveAt("across the end", "lea", List.of(), "2026-10-17T17:00:00Z", "2026-10-17T19:00:00Z");
        saveAt("after", "lea", List.of(), "2026-10-17T18:00:00Z", "2026-10-17T19:00:00Z");

        assertThat(occupyingTitles(List.of("lea"), "2026-10-17T10:00:00Z", "2026-10-17T18:00:00Z"))
                .containsExactlyInAnyOrder("across the start", "inside", "across the end");
    }

    @Test
    @DisplayName("an event without an end lasts an hour")
    void findOccupying_shouldGiveAnEventWithoutAnEndOneHour() {
        saveAt("started 30 min before, still running", "lea", List.of(), "2026-10-17T09:30:00Z", null);
        saveAt("started 2 hours before, over", "lea", List.of(), "2026-10-17T08:00:00Z", null);

        assertThat(occupyingTitles(List.of("lea"), "2026-10-17T10:00:00Z", "2026-10-17T18:00:00Z"))
                .containsExactly("started 30 min before, still running");
    }

    private static final GeoBox LYON = new GeoBox(45.70, 4.77, 45.81, 4.92);
    private static final Instant MAP_NOW = Instant.parse("2026-10-11T16:00:00Z");
    private static final GeoPoint TRANSBORDEUR = new GeoPoint(45.784, 4.8608);

    private void placed(String title, String organizer, Visibility visibility, GeoPoint where, String start, String end) {
        adapter.save(Event.builder()
                .title(title)
                .organizerId(organizer)
                .startDate(Instant.parse(start))
                .endDate(end == null ? null : Instant.parse(end))
                .location(title)
                .coordinates(where)
                .participantIds(new ArrayList<>())
                .visibility(visibility)
                .build()).block();
    }

    private List<String> mapTitles(String userId, List<String> friendIds, int limit) {
        return adapter.findOnMap(userId, friendIds, LYON, MAP_NOW, limit).map(Event::getTitle).collectList().block();
    }

    @Test
    @DisplayName("the map keeps what is inside the area, ignoring places elsewhere and events without a point")
    void findOnMap_shouldKeepEventsInsideTheArea() {
        placed("Concert", "lea", Visibility.PUBLIC, TRANSBORDEUR, "2026-10-12T19:00:00Z", "2026-10-12T23:00:00Z");
        placed("Biarritz", "lea", Visibility.PUBLIC, new GeoPoint(43.48, -1.56), "2026-10-12T10:00:00Z", null);
        placed("Typed only", "lea", Visibility.PUBLIC, null, "2026-10-12T10:00:00Z", null);

        assertThat(mapTitles("camille", List.of(), 200)).containsExactly("Concert");
    }

    @Test
    @DisplayName("the map applies the same visibility as the feed, and shows the caller's own events")
    void findOnMap_shouldApplyTheVisibility() {
        placed("Mine, private", "camille", Visibility.PRIVATE, TRANSBORDEUR, "2026-10-12T10:00:00Z", null);
        placed("Friend's", "lea", Visibility.FRIENDS, TRANSBORDEUR, "2026-10-12T11:00:00Z", null);
        placed("Stranger's", "zoe", Visibility.FRIENDS, TRANSBORDEUR, "2026-10-12T12:00:00Z", null);
        placed("Friend's, private", "lea", Visibility.PRIVATE, TRANSBORDEUR, "2026-10-12T13:00:00Z", null);

        assertThat(mapTitles("camille", List.of("lea"), 200)).containsExactly("Mine, private", "Friend's");
    }

    @Test
    @DisplayName("the map shows events the caller joined, even private ones")
    void findOnMap_shouldShowJoinedEvents() {
        placed("Private dinner", "zoe", Visibility.PRIVATE, TRANSBORDEUR, "2026-10-12T19:00:00Z", null);
        String id = adapter.findOnMap("zoe", List.of(), LYON, MAP_NOW, 10).blockFirst().getId();
        adapter.addParticipant(id, "camille").block();

        assertThat(mapTitles("camille", List.of(), 200)).containsExactly("Private dinner");
    }

    @Test
    @DisplayName("the map drops what is over, keeps what is still running, soonest first, up to the limit")
    void findOnMap_shouldKeepUpcomingAndOngoingEventsSoonestFirst() {
        placed("Later", "lea", Visibility.PUBLIC, TRANSBORDEUR, "2026-10-13T10:00:00Z", null);
        placed("Running", "lea", Visibility.PUBLIC, TRANSBORDEUR, "2026-10-11T15:00:00Z", "2026-10-11T17:00:00Z");
        placed("Over", "lea", Visibility.PUBLIC, TRANSBORDEUR, "2026-10-11T10:00:00Z", "2026-10-11T12:00:00Z");
        placed("No end, started 30 min ago", "lea", Visibility.PUBLIC, TRANSBORDEUR, "2026-10-11T15:30:00Z", null);
        placed("No end, started 2 hours ago", "lea", Visibility.PUBLIC, TRANSBORDEUR, "2026-10-11T14:00:00Z", null);

        assertThat(mapTitles("camille", List.of(), 200)).containsExactly("Running", "No end, started 30 min ago", "Later");
        assertThat(mapTitles("camille", List.of(), 2)).containsExactly("Running", "No end, started 30 min ago");
    }

    @Test
    void findInvolving_shouldFindWhatTheUserOrganizesOrJoins_andNothingElse() {
        Event organized = anEvent("Organized");
        Event joined = anEvent("Joined");
        anEvent("Someone else's");
        adapter.addParticipant(joined.getId(), "guest-1").block();
        adapter.addParticipant(organized.getId(), "guest-2").block();

        StepVerifier.create(adapter.findInvolving("guest-1").map(Event::getTitle).collectList())
                .assertNext(titles -> assertThat(titles).containsExactly("Joined"))
                .verifyComplete();
        StepVerifier.create(adapter.findInvolving(ORGANIZER).count())
                .expectNext(3L)
                .verifyComplete();
    }

    @Test
    @DisplayName("only the organizer's update reaches the cover")
    void setImage_shouldFilterOnTheOrganizer() {
        Event saved = anEvent("Standup");

        StepVerifier.create(adapter.setImage(saved.getId(), "someone-else", "events/x/cover-1.jpg")).verifyComplete();
        StepVerifier.create(adapter.setImage(saved.getId(), ORGANIZER, "events/x/cover-1.jpg"))
                .assertNext(updated -> assertThat(updated.getImage()).isEqualTo("events/x/cover-1.jpg"))
                .verifyComplete();
    }

    @Test
    void setImage_shouldClearTheCover_whenGivenNull() {
        Event saved = anEvent("Standup");
        adapter.setImage(saved.getId(), ORGANIZER, "events/x/cover-1.jpg").block();

        StepVerifier.create(adapter.setImage(saved.getId(), ORGANIZER, null))
                .assertNext(updated -> assertThat(updated.getImage()).isNull())
                .verifyComplete();
    }

    @Test
    void setImage_shouldBeEmpty_forAnUnknownEvent() {
        StepVerifier.create(adapter.setImage("64b7f9f2e4b0a1c2d3e4f5a6", ORGANIZER, "events/x/cover-1.jpg")).verifyComplete();
    }
}
