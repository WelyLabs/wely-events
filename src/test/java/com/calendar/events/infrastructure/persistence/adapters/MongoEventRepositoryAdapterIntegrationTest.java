package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    private static final PollSlot THURSDAY = new PollSlot("thu", Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"));
    private static final PollSlot FRIDAY = new PollSlot("fri", Instant.parse("2026-10-17T17:00:00Z"), Instant.parse("2026-10-17T19:00:00Z"));

    private Event aPoll(String title, String organizer, List<String> participants) {
        return adapter.save(Event.builder()
                .title(title)
                .organizerId(organizer)
                .startDate(THURSDAY.start())
                .endDate(THURSDAY.end())
                .location("Somewhere")
                .participantIds(new ArrayList<>(participants))
                .visibility(Visibility.PUBLIC)
                .poll(new Poll(PollStatus.OPEN, List.of(THURSDAY, FRIDAY), null, null))
                .build()).block();
    }

    private EventEntity stored(String id) {
        return mongoTemplate.findById(id, EventEntity.class).block();
    }

    @Test
    void save_shouldRoundTripThePoll() {
        Event saved = aPoll("Dinner", ORGANIZER, List.of());

        StepVerifier.create(adapter.findById(saved.getId()))
                .assertNext(found -> {
                    assertThat(found.isPolling()).isTrue();
                    assertThat(found.getPoll().slots()).containsExactly(THURSDAY, FRIDAY);
                    assertThat(found.getPoll().ballots()).isEmpty();
                })
                .verifyComplete();
        assertThat(stored(saved.getId()).getDateFixSynced()).isNull();
    }

    @Test
    void castBallot_shouldReplaceTheVotersWholeBallot() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES, "fri", PollAnswer.NO)).block();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of("fri", PollAnswer.MAYBE)))
                .assertNext(updated -> assertThat(updated.getPoll().ballots())
                        .containsExactly(Map.entry("lea", Map.of("fri", PollAnswer.MAYBE))))
                .verifyComplete();
    }

    @Test
    void castBallot_shouldNotLetTwoVotersOverwriteEachOther() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();

        StepVerifier.create(Flux.range(1, 20)
                        .flatMap(i -> adapter.castBallot(id, "voter-" + i, Map.of("thu", PollAnswer.YES)), 10))
                .expectNextCount(20)
                .verifyComplete();

        StepVerifier.create(adapter.findById(id))
                .assertNext(found -> {
                    assertThat(found.getPoll().ballots()).hasSize(20);
                    assertThat(found.getPoll().yesVotersOf("thu")).hasSize(20);
                })
                .verifyComplete();
    }

    @Test
    void castBallot_shouldAnswerTheEvent_whenTheSameBallotIsCastTwice() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES)).block();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES)))
                .assertNext(updated -> assertThat(updated.getPoll().yesVotersOf("thu")).containsExactly("lea"))
                .verifyComplete();
    }

    @Test
    void castBallot_shouldWithdrawTheBallot_whenGivenNoAnswer() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES)).block();
        adapter.castBallot(id, "hugo", Map.of("thu", PollAnswer.NO)).block();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of()))
                .assertNext(updated -> assertThat(updated.getPoll().ballots()).containsOnlyKeys("hugo"))
                .verifyComplete();
    }

    @Test
    void castBallot_shouldBeEmpty_onceTheDateIsFixed_orWithoutPoll() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.fixPollDate(id, ORGANIZER, THURSDAY, List.of()).block();
        String withoutPoll = anEvent("Standup").getId();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES))).verifyComplete();
        StepVerifier.create(adapter.castBallot(withoutPoll, "lea", Map.of("thu", PollAnswer.YES))).verifyComplete();
        assertThat(adapter.findById(id).block().getPoll().ballots()).isEmpty();
    }

    @Test
    void fixPollDate_shouldMoveTheDates_subscribeTheYesVoters_andFlagBothRelays() {
        String id = aPoll("Dinner", ORGANIZER, List.of("hugo")).getId();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("groupSynced", true).set("groupVersion", 3L),
                EventEntity.class).block();

        StepVerifier.create(adapter.fixPollDate(id, ORGANIZER, FRIDAY, List.of("lea", "hugo")))
                .assertNext(fixed -> {
                    assertThat(fixed.isPolling()).isFalse();
                    assertThat(fixed.getPoll().status()).isEqualTo(PollStatus.CLOSED);
                    assertThat(fixed.getPoll().fixedSlotId()).isEqualTo("fri");
                    assertThat(fixed.getStartDate()).isEqualTo(FRIDAY.start());
                    assertThat(fixed.getEndDate()).isEqualTo(FRIDAY.end());
                    assertThat(fixed.getParticipantIds()).containsExactly("hugo", "lea");
                })
                .verifyComplete();
        EventEntity stored = stored(id);
        assertThat(stored.getGroupVersion()).isEqualTo(4L);
        assertThat(stored.getGroupSynced()).isFalse();
        assertThat(stored.getDateFixSynced()).isFalse();
    }

    @Test
    void fixPollDate_shouldMatchOnlyOnce() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.fixPollDate(id, ORGANIZER, THURSDAY, List.of()).block();

        StepVerifier.create(adapter.fixPollDate(id, ORGANIZER, FRIDAY, List.of("lea"))).verifyComplete();
        Event found = adapter.findById(id).block();
        assertThat(found.getPoll().fixedSlotId()).isEqualTo("thu");
        assertThat(found.getParticipantIds()).isEmpty();
        assertThat(stored(id).getGroupVersion()).isEqualTo(1L);
    }

    @Test
    void fixPollDate_shouldBeEmpty_forSomeoneOtherThanTheOrganizer() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();

        StepVerifier.create(adapter.fixPollDate(id, "lea", FRIDAY, List.of())).verifyComplete();
        assertThat(adapter.findById(id).block().isPolling()).isTrue();
    }

    @Test
    void fixPollDate_shouldCountAsANewDate_withNoPreviousOne() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("rescheduledFromStart", THURSDAY.start()).set("dateFixVersion", 2L),
                EventEntity.class).block();

        adapter.fixPollDate(id, ORGANIZER, FRIDAY, List.of()).block();

        EventEntity stored = stored(id);
        assertThat(stored.getDateFixVersion()).isEqualTo(3L);
        assertThat(stored.getRescheduledFromStart()).isNull();
        assertThat(stored.getRescheduledFromEnd()).isNull();
    }

    @Test
    void reschedule_shouldMoveTheDates_keepThePreviousOnes_andFlagTheAnnouncement() {
        Event read = saveAt("Bowling", ORGANIZER, List.of("lea"), "2026-10-16T17:00:00Z", "2026-10-16T19:00:00Z");
        TimeRange later = new TimeRange(Instant.parse("2026-10-16T18:00:00Z"), Instant.parse("2026-10-16T20:00:00Z"));

        StepVerifier.create(adapter.reschedule(read, later, true))
                .assertNext(moved -> {
                    assertThat(moved.getStartDate()).isEqualTo(later.start());
                    assertThat(moved.getEndDate()).isEqualTo(later.end());
                    assertThat(moved.getParticipantIds()).containsExactly("lea");
                })
                .verifyComplete();
        EventEntity stored = stored(read.getId());
        assertThat(stored.getRescheduledFromStart()).isEqualTo(Instant.parse("2026-10-16T17:00:00Z"));
        assertThat(stored.getRescheduledFromEnd()).isEqualTo(Instant.parse("2026-10-16T19:00:00Z"));
        assertThat(stored.getDateFixSynced()).isFalse();
        assertThat(stored.getDateFixVersion()).isEqualTo(1L);
    }

    @Test
    void reschedule_shouldLeaveNothingToAnnounce_whenAskedNotTo() {
        Event read = saveAt("Alone", ORGANIZER, List.of(), "2026-10-16T17:00:00Z", null);

        adapter.reschedule(read, new TimeRange(FRIDAY.start(), FRIDAY.end()), false).block();

        EventEntity stored = stored(read.getId());
        assertThat(stored.getStartDate()).isEqualTo(FRIDAY.start());
        assertThat(stored.getDateFixSynced()).isNull();
    }

    @Test
    void reschedule_shouldLandOnce_whenTwoMovesStartFromTheSameRead() {
        Event read = saveAt("Bowling", ORGANIZER, List.of(), "2026-10-16T17:00:00Z", "2026-10-16T19:00:00Z");
        adapter.reschedule(read, new TimeRange(FRIDAY.start(), FRIDAY.end()), false).block();

        StepVerifier.create(adapter.reschedule(read, new TimeRange(THURSDAY.start().plusSeconds(3600),
                THURSDAY.end().plusSeconds(3600)), false)).verifyComplete();
        assertThat(adapter.findById(read.getId()).block().getStartDate()).isEqualTo(FRIDAY.start());
    }

    @Test
    void reschedule_shouldBeEmpty_forAnOpenPollOrSomeoneElse() {
        Event poll = aPoll("Dinner", ORGANIZER, List.of());
        Event plain = saveAt("Bowling", ORGANIZER, List.of(), "2026-10-16T17:00:00Z", "2026-10-16T19:00:00Z");
        Event notMine = Event.builder().id(plain.getId()).organizerId("lea").startDate(plain.getStartDate()).build();

        StepVerifier.create(adapter.reschedule(poll, new TimeRange(FRIDAY.start(), FRIDAY.end()), false)).verifyComplete();
        StepVerifier.create(adapter.reschedule(notMine, new TimeRange(FRIDAY.start(), FRIDAY.end()), false)).verifyComplete();
        assertThat(adapter.findById(poll.getId()).block().getStartDate()).isEqualTo(THURSDAY.start());
        assertThat(adapter.findById(plain.getId()).block().getStartDate()).isEqualTo(THURSDAY.start());
    }

    @Test
    void findOccupying_shouldIgnoreOpenPolls_butCountFixedOnes() {
        String open = aPoll("Still voting", "lea", List.of()).getId();
        String fixed = aPoll("Fixed", "lea", List.of()).getId();
        adapter.fixPollDate(fixed, "lea", THURSDAY, List.of()).block();

        assertThat(occupyingTitles(List.of("lea"), "2026-10-16T00:00:00Z", "2026-10-18T00:00:00Z"))
                .containsExactly("Fixed");
        assertThat(open).isNotNull();
    }

    @Test
    void findOpenPollsInvolving_shouldFindWhatTheUserOrganizesJoinsOrVotedOn_andNothingElse() {
        aPoll("Organized", "lea", List.of());
        aPoll("Joined", "hugo", List.of("lea"));
        String voted = aPoll("Voted on", "hugo", List.of()).getId();
        adapter.castBallot(voted, "lea", Map.of("fri", PollAnswer.NO)).block();
        aPoll("Someone else's", "hugo", List.of("zoe"));
        String fixed = aPoll("Fixed already", "lea", List.of()).getId();
        adapter.fixPollDate(fixed, "lea", THURSDAY, List.of()).block();
        visible("No poll", "lea", Visibility.PUBLIC, List.of());

        assertThat(adapter.findOpenPollsInvolving("lea").map(Event::getTitle).collectList().block())
                .containsExactlyInAnyOrder("Organized", "Joined", "Voted on");
    }

    private static final Instant OVERVIEW_NOW = Instant.parse("2026-10-14T12:00:00Z");

    private List<String> titles(Flux<Event> events) {
        return events.map(Event::getTitle).collectList().block();
    }

    @Test
    @DisplayName("events both are on, as organizer or participant; never those only one of them is on")
    void findUpcomingInvolvingBoth_shouldMatchBothUsersInEitherRole() {
        saveAt("lea organizes, hugo joins", "lea", List.of("hugo"), "2026-10-20T10:00:00Z", "2026-10-20T11:00:00Z");
        saveAt("hugo organizes, lea joins", "hugo", List.of("lea"), "2026-10-21T10:00:00Z", "2026-10-21T11:00:00Z");
        saveAt("both join", "zoe", List.of("lea", "hugo"), "2026-10-22T10:00:00Z", "2026-10-22T11:00:00Z");
        saveAt("lea only", "lea", List.of("zoe"), "2026-10-23T10:00:00Z", "2026-10-23T11:00:00Z");
        saveAt("hugo only", "zoe", List.of("hugo"), "2026-10-24T10:00:00Z", "2026-10-24T11:00:00Z");

        assertThat(titles(adapter.findUpcomingInvolvingBoth("lea", "hugo", OVERVIEW_NOW, 10)))
                .containsExactly("lea organizes, hugo joins", "hugo organizes, lea joins", "both join");
    }

    @Test
    @DisplayName("what is still on is upcoming, soonest first and capped; an event without an end lasts an hour")
    void findUpcomingInvolvingBoth_shouldKeepEventsNotOverYet_soonestFirst_upToTheLimit() {
        saveAt("later", "lea", List.of("hugo"), "2026-10-30T10:00:00Z", "2026-10-30T11:00:00Z");
        saveAt("running", "lea", List.of("hugo"), "2026-10-14T11:00:00Z", "2026-10-14T13:00:00Z");
        saveAt("no end, started 30 min ago", "lea", List.of("hugo"), "2026-10-14T11:30:00Z", null);
        saveAt("no end, started 2 hours ago", "lea", List.of("hugo"), "2026-10-14T10:00:00Z", null);
        saveAt("over", "lea", List.of("hugo"), "2026-10-13T10:00:00Z", "2026-10-13T11:00:00Z");
        saveAt("tomorrow", "hugo", List.of("lea"), "2026-10-15T10:00:00Z", "2026-10-15T11:00:00Z");

        assertThat(titles(adapter.findUpcomingInvolvingBoth("lea", "hugo", OVERVIEW_NOW, 10)))
                .containsExactly("running", "no end, started 30 min ago", "tomorrow", "later");
        assertThat(titles(adapter.findUpcomingInvolvingBoth("hugo", "lea", OVERVIEW_NOW, 2)))
                .containsExactly("running", "no end, started 30 min ago");
    }

    @Test
    @DisplayName("memories are what both were on and is over, most recent first and capped")
    void findPastInvolvingBoth_shouldKeepEventsOver_mostRecentFirst_upToTheLimit() {
        saveAt("last month", "lea", List.of("hugo"), "2026-09-14T10:00:00Z", "2026-09-14T11:00:00Z");
        saveAt("yesterday", "hugo", List.of("lea"), "2026-10-13T10:00:00Z", "2026-10-13T11:00:00Z");
        saveAt("no end, started 2 hours ago", "zoe", List.of("lea", "hugo"), "2026-10-14T10:00:00Z", null);
        saveAt("no end, started 30 min ago", "lea", List.of("hugo"), "2026-10-14T11:30:00Z", null);
        saveAt("running", "lea", List.of("hugo"), "2026-10-14T11:00:00Z", "2026-10-14T13:00:00Z");
        saveAt("lea alone", "lea", List.of(), "2026-10-12T10:00:00Z", "2026-10-12T11:00:00Z");

        assertThat(titles(adapter.findPastInvolvingBoth("lea", "hugo", OVERVIEW_NOW, 10)))
                .containsExactly("no end, started 2 hours ago", "yesterday", "last month");
        assertThat(titles(adapter.findPastInvolvingBoth("lea", "hugo", OVERVIEW_NOW, 2)))
                .containsExactly("no end, started 2 hours ago", "yesterday");
    }

    @Test
    @DisplayName("outings together count every past event both were on, beyond the memories shown")
    void countPastInvolvingBoth_shouldCountEveryPastEventBothWereOn() {
        for (int day = 1; day <= 8; day++) {
            String start = "2026-09-%02dT10:00:00Z".formatted(day);
            saveAt("outing " + day, day % 2 == 0 ? "lea" : "hugo", List.of(day % 2 == 0 ? "hugo" : "lea"), start, null);
        }
        saveAt("upcoming together", "lea", List.of("hugo"), "2026-10-20T10:00:00Z", null);
        saveAt("lea alone", "lea", List.of("zoe"), "2026-09-10T10:00:00Z", null);

        StepVerifier.create(adapter.countPastInvolvingBoth("lea", "hugo", OVERVIEW_NOW)).expectNext(8L).verifyComplete();
        StepVerifier.create(adapter.countPastInvolvingBoth("lea", "nobody", OVERVIEW_NOW)).expectNext(0L).verifyComplete();
    }

    @Test
    @DisplayName("a friend's upcoming events are those they organize or join and that are not over, soonest first")
    void findUpcomingInvolving_shouldKeepTheUsersEventsNotOverYet_soonestFirst() {
        saveAt("hugo joins, later", "zoe", List.of("hugo"), "2026-10-25T10:00:00Z", "2026-10-25T11:00:00Z");
        saveAt("hugo organizes, tomorrow", "hugo", List.of(), "2026-10-15T10:00:00Z", null);
        saveAt("hugo, over", "hugo", List.of(), "2026-10-13T10:00:00Z", "2026-10-13T11:00:00Z");
        saveAt("someone else's", "zoe", List.of("lea"), "2026-10-16T10:00:00Z", null);

        assertThat(titles(adapter.findUpcomingInvolving("hugo", OVERVIEW_NOW)))
                .containsExactly("hugo organizes, tomorrow", "hugo joins, later");
    }
}
