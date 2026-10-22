package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.EventDetails;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoEventRepositoryAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
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
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    private Event aSubscribedEvent(String title, String start, String end) {
        return adapter.save(Event.builder()
                .title(title)
                .organizerId(ORGANIZER)
                .startDate(Instant.parse(start))
                .endDate(end == null ? null : Instant.parse(end))
                .participantIds(new ArrayList<>(List.of("alice")))
                .build()).block();
    }

    @Test
    @DisplayName("the range query keeps an event inside it and drops the ones outside")
    void findByParticipantIdBetween_shouldKeepOnlyOverlappingEvents() {
        aSubscribedEvent("inside", "2026-10-21T10:00:00Z", "2026-10-21T12:00:00Z");
        aSubscribedEvent("before", "2026-10-19T10:00:00Z", "2026-10-19T12:00:00Z");
        aSubscribedEvent("after", "2026-10-23T10:00:00Z", "2026-10-23T12:00:00Z");

        StepVerifier.create(adapter.findByParticipantIdBetween("alice",
                        Instant.parse("2026-10-21T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z"))
                        .map(Event::getTitle))
                .expectNext("inside")
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an event straddling the start of the range is kept")
    void findByParticipantIdBetween_shouldKeepAnEventStraddlingTheRange() {
        aSubscribedEvent("straddles", "2026-10-20T20:00:00Z", "2026-10-21T04:00:00Z");

        StepVerifier.create(adapter.findByParticipantIdBetween("alice",
                        Instant.parse("2026-10-21T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z")))
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an event with no end is matched on its start alone")
    void findByParticipantIdBetween_shouldKeepAnEventWithoutEnd() {
        aSubscribedEvent("open", "2026-10-21T23:00:00Z", null);

        StepVerifier.create(adapter.findByParticipantIdBetween("alice",
                        Instant.parse("2026-10-21T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z")))
                .expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the end of the range is exclusive")
    void findByParticipantIdBetween_shouldTreatTheEndAsExclusive() {
        aSubscribedEvent("onTheEdge", "2026-10-22T00:00:00Z", null);

        StepVerifier.create(adapter.findByParticipantIdBetween("alice",
                        Instant.parse("2026-10-21T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z")))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the range query answers soonest first")
    void findByParticipantIdBetween_shouldSortBySoonest() {
        aSubscribedEvent("later", "2026-10-21T18:00:00Z", null);
        aSubscribedEvent("sooner", "2026-10-21T09:00:00Z", null);

        StepVerifier.create(adapter.findByParticipantIdBetween("alice",
                        Instant.parse("2026-10-21T00:00:00Z"), Instant.parse("2026-10-22T00:00:00Z"))
                        .map(Event::getTitle))
                .expectNext("sooner", "later")
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the range query leaves out an event the caller does not take part in")
    void findByParticipantIdBetween_shouldNotReturnSomeoneElsesEvent() {
        anEvent("not alice's");

        StepVerifier.create(adapter.findByParticipantIdBetween("alice",
                        Instant.parse("2025-06-01T00:00:00Z"), Instant.parse("2025-06-02T00:00:00Z")))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("asking to join records the request without taking part")
    void requestToJoin_shouldOnlyRecordTheRequest() {
        Event saved = anEvent("Surprise");

        StepVerifier.create(adapter.requestToJoin(saved.getId(), "alice"))
                .assertNext(updated -> {
                    assertThat(updated.getRequestedIds()).containsExactly("alice");
                    assertThat(updated.getParticipantIds()).doesNotContain("alice");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("asking twice leaves one request")
    void requestToJoin_shouldBeIdempotent() {
        Event saved = anEvent("Surprise");
        adapter.requestToJoin(saved.getId(), "alice").block();

        StepVerifier.create(adapter.requestToJoin(saved.getId(), "alice"))
                .assertNext(updated -> assertThat(updated.getRequestedIds()).containsExactly("alice"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("withdrawing a request leaves nothing behind")
    void withdrawRequest_shouldRemoveIt() {
        Event saved = anEvent("Surprise");
        adapter.requestToJoin(saved.getId(), "alice").block();

        StepVerifier.create(adapter.withdrawRequest(saved.getId(), "alice"))
                .assertNext(updated -> assertThat(updated.getRequestedIds()).isEmpty())
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("accepting a request makes the asker a participant and clears the request")
    void acceptRequest_shouldTakeTheAskerIn() {
        Event saved = anEvent("Surprise");
        adapter.requestToJoin(saved.getId(), "alice").block();

        StepVerifier.create(adapter.acceptRequest(saved.getId(), ORGANIZER, "alice"))
                .assertNext(updated -> {
                    assertThat(updated.getParticipantIds()).contains("alice");
                    assertThat(updated.getRequestedIds()).isEmpty();
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("only the organizer answers a request")
    void acceptRequest_shouldRefuseAnyoneElse() {
        Event saved = anEvent("Surprise");
        adapter.requestToJoin(saved.getId(), "alice").block();

        StepVerifier.create(adapter.acceptRequest(saved.getId(), "someone-else", "alice"))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(saved.getId()).block().getParticipantIds()).doesNotContain("alice");
    }

    @Test
    @DisplayName("accepting a withdrawn request takes nobody in")
    void acceptRequest_shouldBeEmptyWithoutAStandingRequest() {
        Event saved = anEvent("Surprise");

        StepVerifier.create(adapter.acceptRequest(saved.getId(), ORGANIZER, "alice"))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(saved.getId()).block().getParticipantIds()).doesNotContain("alice");
    }

    @Test
    @DisplayName("declining a request removes it and takes nobody in")
    void declineRequest_shouldRemoveTheRequestOnly() {
        Event saved = anEvent("Surprise");
        adapter.requestToJoin(saved.getId(), "alice").block();

        StepVerifier.create(adapter.declineRequest(saved.getId(), ORGANIZER, "alice"))
                .assertNext(updated -> {
                    assertThat(updated.getRequestedIds()).isEmpty();
                    assertThat(updated.getParticipantIds()).doesNotContain("alice");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("an unknown id yields nothing rather than a blank event")
    void findById_shouldBeEmptyForAnUnknownId() {
        StepVerifier.create(adapter.findById("64b7f9f2e4b0a1c2d3e4f5a6")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("subscribing adds the user to the event, and returns the updated document")
    void addParticipant_shouldReturnTheEventAfterTheUpdate() {
        // returnNew(true) is the difference between showing the caller their subscription and
        // showing them the state before it.
        Event saved = anEvent("Standup");

        StepVerifier.create(adapter.addParticipant(saved.getId(), "alice"))
                .assertNext(updated -> assertThat(updated.getParticipantIds()).containsExactly("alice"))
                .expectComplete().verify(Duration.ofSeconds(10));
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
                .expectComplete().verify(Duration.ofSeconds(10));
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
                .expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(adapter.findById(saved.getId()))
                .assertNext(found -> assertThat(found.getParticipantIds()).hasSize(20))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("unsubscribing removes only that user")
    void removeParticipant_shouldPullOnlyTheGivenUser() {
        Event saved = anEvent("Standup");
        adapter.addParticipant(saved.getId(), "alice").block();
        adapter.addParticipant(saved.getId(), "bob").block();

        StepVerifier.create(adapter.removeParticipant(saved.getId(), "alice"))
                .assertNext(updated -> assertThat(updated.getParticipantIds()).containsExactly("bob"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("unsubscribing someone who never subscribed changes nothing")
    void removeParticipant_shouldTolerateANonParticipant() {
        Event saved = anEvent("Standup");
        adapter.addParticipant(saved.getId(), "alice").block();

        StepVerifier.create(adapter.removeParticipant(saved.getId(), "bob"))
                .assertNext(updated -> assertThat(updated.getParticipantIds()).containsExactly("alice"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("touching a missing event yields nothing rather than creating one")
    void addParticipant_shouldNotUpsert() {
        // An update without upsert: subscribing to an event that does not exist must not
        // bring it into being.
        StepVerifier.create(adapter.addParticipant("64b7f9f2e4b0a1c2d3e4f5a6", "alice"))
                .expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(adapter.findAll()).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the subscribed list holds only the events the caller takes part in")
    void findByParticipantId_shouldKeepJoinedEventsOnly() {
        Event subscribed = anEvent("Subscribed");
        anEvent("Not subscribed");
        adapter.addParticipant(subscribed.getId(), "alice").block();

        StepVerifier.create(adapter.findByParticipantId("alice"))
                .assertNext(event -> assertThat(event.getId()).isEqualTo(subscribed.getId()))
                .expectComplete().verify(Duration.ofSeconds(10));
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

    private String privateDinnerOf(String organizer) {
        return adapter.save(Event.builder()
                .title("Private dinner")
                .organizerId(organizer)
                .startDate(Instant.parse("2026-10-17T10:00:00Z"))
                .location("Lyon")
                .participantIds(new ArrayList<>())
                .visibility(Visibility.PRIVATE)
                .build()).block().getId();
    }

    @Test
    @DisplayName("an invitation shows a private event in the invitee's feed, until they answer it")
    void findFeed_shouldShowAPrivateEventToItsInvitees() {
        String id = privateDinnerOf("bob");

        assertThat(adapter.invite(id, "bob", "alice").block().getInvitedIds()).containsExactly("alice");
        assertThat(feedTitles("alice", List.of())).containsExactly("Private dinner");
        assertThat(feedTitles("carol", List.of("bob"))).isEmpty();

        adapter.withdrawInvitation(id, "alice").block();
        assertThat(feedTitles("alice", List.of())).isEmpty();
    }

    @Test
    @DisplayName("inviting twice is one invitation, and only the organizer invites")
    void invite_shouldBeIdempotent_andFilterOnTheOrganizer() {
        String id = privateDinnerOf("bob");

        adapter.invite(id, "bob", "alice").block();
        adapter.invite(id, "bob", "alice").block();
        adapter.invite(id, "carol", "dave").block();

        assertThat(adapter.findById(id).block().getInvitedIds()).containsExactly("alice");
    }

    @Test
    @DisplayName("nobody is invited to an event they already joined")
    void invite_shouldSkipSomeoneTakingPart() {
        String id = privateDinnerOf("bob");
        adapter.addParticipant(id, "alice").block();

        assertThat(adapter.invite(id, "bob", "alice").block().getInvitedIds()).isEmpty();
    }

    @Test
    @DisplayName("joining answers the invitation: the invitee leaves the invited for the participants")
    void addParticipant_shouldTakeTheUserOffTheInvited() {
        String id = privateDinnerOf("bob");
        adapter.invite(id, "bob", "alice").block();
        adapter.invite(id, "bob", "carol").block();

        Event joined = adapter.addParticipant(id, "alice").block();

        assertThat(joined.getParticipantIds()).containsExactly("alice");
        assertThat(joined.getInvitedIds()).containsExactly("carol");
    }

    @Test
    @DisplayName("an erasure takes the user off every invitation")
    void forgetInvitations_shouldPullTheUserEverywhere() {
        String first = privateDinnerOf("bob");
        String second = privateDinnerOf("carol");
        adapter.invite(first, "bob", "alice").block();
        adapter.invite(second, "carol", "alice").block();
        adapter.invite(second, "carol", "dave").block();

        assertThat(adapter.forgetInvitations("alice").block()).isEqualTo(2);
        assertThat(adapter.findById(first).block().getInvitedIds()).isEmpty();
        assertThat(adapter.findById(second).block().getInvitedIds()).containsExactly("dave");
    }

    @Test
    @DisplayName("withdrawing an invitation of an unknown event yields nothing")
    void withdrawInvitation_shouldBeEmpty_forAnUnknownEvent() {
        StepVerifier.create(adapter.withdrawInvitation("64b7f9f2e4b0a1c2d3e4f5a6", "alice")).expectComplete().verify(Duration.ofSeconds(10));
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
    @DisplayName("the organizer's deletion removes the event and gives it back with its group version")
    void deleteByOrganizer_shouldRemoveTheEvent_andReturnItAsItWas() {
        Event saved = anEvent("Standup");
        adapter.addParticipant(saved.getId(), "alice").block();

        StepVerifier.create(adapter.deleteByOrganizer(saved.getId(), ORGANIZER))
                .assertNext(removed -> {
                    assertThat(removed.getTitle()).isEqualTo("Standup");
                    assertThat(removed.getGroupVersion()).isEqualTo(1L);
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findById(saved.getId())).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void deleteByOrganizer_shouldLeaveSomeoneElsesEvent() {
        Event saved = anEvent("Standup");

        StepVerifier.create(adapter.deleteByOrganizer(saved.getId(), "alice")).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(saved.getId()).block()).isNotNull();
    }

    @Test
    void deleteIfAlone_shouldDelete_whenOnlyTheOrganizerTakesPart() {
        String listed = anEvent("Listed").getId();
        adapter.addParticipant(listed, ORGANIZER).block();
        String unlisted = anEvent("Unlisted").getId();

        StepVerifier.create(adapter.deleteIfAlone(listed, ORGANIZER, Set.of()))
                .assertNext(removed -> assertThat(removed.getId()).isEqualTo(listed))
                .expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.deleteIfAlone(unlisted, ORGANIZER, Set.of()))
                .assertNext(removed -> assertThat(removed.getId()).isEqualTo(unlisted))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findAll().collectList().block()).isEmpty();
    }

    @Test
    void deleteIfAlone_shouldKeepTheEvent_whenSomeoneJoined() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "alice").block();

        StepVerifier.create(adapter.deleteIfAlone(id, ORGANIZER, Set.of())).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.deleteIfAlone(id, "alice", Set.of())).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(id).block()).isNotNull();
    }

    @Test
    void deleteIfAlone_shouldDelete_whenTheOtherParticipantsAreAllFormerMembers() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "gone").block();
        adapter.addParticipant(id, "demo").block();

        StepVerifier.create(adapter.deleteIfAlone(id, ORGANIZER, Set.of("gone", "demo")))
                .assertNext(removed -> assertThat(removed.getId()).isEqualTo(id))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(id).block()).isNull();
    }

    @Test
    void deleteIfAlone_shouldKeepTheEvent_whenALiveParticipantRemains() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "gone").block();
        adapter.addParticipant(id, "alice").block();

        StepVerifier.create(adapter.deleteIfAlone(id, ORGANIZER, Set.of("gone"))).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(id).block()).isNotNull();
    }

    @Test
    void addParticipant_shouldAppendInJoinOrder() {
        // The successor of a deleted organizer is the earliest participant: the order is the join order.
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "zoe").block();
        adapter.addParticipant(id, "alice").block();
        adapter.addParticipant(id, "mia").block();
        adapter.removeParticipant(id, "zoe").block();
        adapter.addParticipant(id, "zoe").block();

        assertThat(stored(id).getParticipantIds()).containsExactly(ORGANIZER, "alice", "mia", "zoe");
    }

    @Test
    void findOrganizedBy_shouldReturnPastAndUpcomingEvents() {
        Instant at = Instant.parse("2026-10-15T14:00:00Z");
        dated("past", ORGANIZER, at.minusSeconds(86_400), at.minusSeconds(82_800), List.of(ORGANIZER));
        dated("upcoming", ORGANIZER, at.plusSeconds(86_400), null, List.of(ORGANIZER));
        dated("someone else's", "alice", at.plusSeconds(3600), null, List.of("alice", ORGANIZER));

        assertThat(titles(adapter.findOrganizedBy(ORGANIZER))).containsExactly("past", "upcoming");
    }

    @Test
    void handOverKeepingFormer_shouldSwapTheOrganizer_keepTheFormer_andFlagTheGroup() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "alice").block();
        markGroupSynced(id);

        StepVerifier.create(adapter.handOverKeepingFormer(id, ORGANIZER, "alice"))
                .assertNext(event -> {
                    assertThat(event.getOrganizerId()).isEqualTo("alice");
                    assertThat(event.getParticipantIds()).containsExactly(ORGANIZER, "alice");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        EventEntity stored = stored(id);
        assertThat(stored.getGroupVersion()).isEqualTo(3L);
        assertThat(stored.getGroupSynced()).isFalse();
    }

    @Test
    void handOverKeepingFormer_shouldChangeNothing_whenTheSuccessorLeft() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "alice").block();

        StepVerifier.create(adapter.handOverKeepingFormer(id, ORGANIZER, "bob")).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.handOverKeepingFormer(id, "alice", ORGANIZER)).expectComplete().verify(Duration.ofSeconds(10));
        EventEntity stored = stored(id);
        assertThat(stored.getOrganizerId()).isEqualTo(ORGANIZER);
        assertThat(stored.getGroupVersion()).isEqualTo(2L);
    }

    private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:00:00Z");

    private String dated(String title, String organizer, Instant start, Instant end, List<String> participants) {
        return adapter.save(Event.builder().title(title).organizerId(organizer).startDate(start).endDate(end)
                .location("Somewhere").participantIds(new ArrayList<>(participants)).visibility(Visibility.PUBLIC)
                .build()).block().getId();
    }

    private void markGroupSynced(String id) {
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("groupSynced", true).set("groupVersion", 2L), EventEntity.class).block();
    }

    @Test
    void leaveUpcoming_shouldPullTheUserFromEventsNotOver_andFlagEachGroup() {
        String endsLater = dated("ends later", "alice", DELETED_AT.minusSeconds(3600), DELETED_AT.plusSeconds(3600),
                List.of("alice", "gone"));
        String endsExactlyThen = dated("ends exactly then", "alice", DELETED_AT.minusSeconds(3600), DELETED_AT,
                List.of("alice", "gone"));
        String noEndStartedLessThanAnHourAgo = dated("no end, started 30 min ago", "alice",
                DELETED_AT.minusSeconds(1800), null, List.of("gone", "alice"));
        List.of(endsLater, endsExactlyThen, noEndStartedLessThanAnHourAgo).forEach(this::markGroupSynced);

        StepVerifier.create(adapter.leaveUpcoming("gone", DELETED_AT)).expectNext(3L).expectComplete().verify(Duration.ofSeconds(10));

        for (String id : List.of(endsLater, endsExactlyThen, noEndStartedLessThanAnHourAgo)) {
            EventEntity stored = stored(id);
            assertThat(stored.getParticipantIds()).containsExactly("alice");
            assertThat(stored.getGroupVersion()).isEqualTo(3L);
            assertThat(stored.getGroupSynced()).isFalse();
        }
    }

    @Test
    void leaveUpcoming_shouldKeepPastEventsAndEventsTheUserOrganizes() {
        String endedBefore = dated("ended before", "alice", DELETED_AT.minusSeconds(7200), DELETED_AT.minusSeconds(1),
                List.of("alice", "gone"));
        String noEndStartedOverAnHourAgo = dated("no end, started 2 h ago", "alice", DELETED_AT.minusSeconds(7200), null,
                List.of("alice", "gone"));
        String organized = dated("organized", "gone", DELETED_AT.plusSeconds(3600), null, List.of("gone", "alice"));
        String elsewhere = dated("someone else's", "alice", DELETED_AT.plusSeconds(3600), null, List.of("alice"));
        List.of(endedBefore, noEndStartedOverAnHourAgo, organized, elsewhere).forEach(this::markGroupSynced);

        StepVerifier.create(adapter.leaveUpcoming("gone", DELETED_AT)).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(stored(endedBefore).getParticipantIds()).containsExactly("alice", "gone");
        assertThat(stored(noEndStartedOverAnHourAgo).getParticipantIds()).containsExactly("alice", "gone");
        assertThat(stored(organized).getParticipantIds()).containsExactly("gone", "alice");
        for (String id : List.of(endedBefore, noEndStartedOverAnHourAgo, organized, elsewhere)) {
            assertThat(stored(id).getGroupSynced()).isTrue();
        }
    }

    @Test
    void leaveUpcoming_shouldTreatAnOpenPollAsUpcoming() {
        String open = aPoll("open, earliest slot long past", "alice", List.of("alice", "gone")).getId();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(open)),
                new Update().set("startDate", DELETED_AT.minusSeconds(86_400)).set("endDate", DELETED_AT.minusSeconds(82_800)),
                EventEntity.class).block();

        StepVerifier.create(adapter.leaveUpcoming("gone", DELETED_AT)).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(stored(open).getParticipantIds()).containsExactly("alice");
    }

    @Test
    void leaveUpcoming_shouldNotBumpAnyVersion_whenReplayed() {
        String id = dated("upcoming", "alice", DELETED_AT.plusSeconds(3600), null, List.of("alice", "gone"));
        adapter.leaveUpcoming("gone", DELETED_AT).block();
        Long version = stored(id).getGroupVersion();

        StepVerifier.create(adapter.leaveUpcoming("gone", DELETED_AT)).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(stored(id).getGroupVersion()).isEqualTo(version);
    }

    @Test
    void removeParticipant_shouldNeverRemoveTheOrganizer() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        Long version = stored(id).getGroupVersion();

        StepVerifier.create(adapter.removeParticipant(id, ORGANIZER))
                .assertNext(event -> assertThat(event.getParticipantIds()).containsExactly(ORGANIZER))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(stored(id).getGroupVersion()).isEqualTo(version);
    }

    @Test
    void handOver_shouldSwapTheOrganizer_andFlagTheGroup_inOneWrite() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "alice").block();
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("groupSynced", true), EventEntity.class).block();

        StepVerifier.create(adapter.handOver(id, ORGANIZER, "alice"))
                .assertNext(event -> {
                    assertThat(event.getOrganizerId()).isEqualTo("alice");
                    assertThat(event.getParticipantIds()).containsExactly("alice");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        EventEntity stored = stored(id);
        assertThat(stored.getGroupVersion()).isEqualTo(3L);
        assertThat(stored.getGroupSynced()).isFalse();
    }

    @Test
    void handOver_shouldChangeNothing_whenTheSuccessorNoLongerTakesPart_orTheOrganizerChanged() {
        String id = anEvent("Standup").getId();
        adapter.addParticipant(id, ORGANIZER).block();
        adapter.addParticipant(id, "alice").block();

        StepVerifier.create(adapter.handOver(id, ORGANIZER, "bob")).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.handOver(id, "alice", ORGANIZER)).expectComplete().verify(Duration.ofSeconds(10));
        EventEntity stored = stored(id);
        assertThat(stored.getOrganizerId()).isEqualTo(ORGANIZER);
        assertThat(stored.getParticipantIds()).containsExactly(ORGANIZER, "alice");
        assertThat(stored.getGroupVersion()).isEqualTo(2L);
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
        return adapter.findOccupying(userIds, Instant.parse(from), Instant.parse(to), "nobody")
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
    @DisplayName("the map shows a private event to whoever is invited to it")
    void findOnMap_shouldShowInvitedEvents() {
        placed("Private dinner", "zoe", Visibility.PRIVATE, TRANSBORDEUR, "2026-10-12T19:00:00Z", null);
        String id = adapter.findOnMap("zoe", List.of(), LYON, MAP_NOW, 10).blockFirst().getId();
        adapter.invite(id, "zoe", "camille").block();

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
                .expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findInvolving(ORGANIZER).count())
                .expectNext(3L)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("only the organizer's update reaches the cover")
    void setImage_shouldFilterOnTheOrganizer() {
        Event saved = anEvent("Standup");

        StepVerifier.create(adapter.setImage(saved.getId(), "someone-else", "events/x/cover-1.jpg")).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.setImage(saved.getId(), ORGANIZER, "events/x/cover-1.jpg"))
                .assertNext(updated -> assertThat(updated.getImage()).isEqualTo("events/x/cover-1.jpg"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void setImage_shouldClearTheCover_whenGivenNull() {
        Event saved = anEvent("Standup");
        adapter.setImage(saved.getId(), ORGANIZER, "events/x/cover-1.jpg").block();

        StepVerifier.create(adapter.setImage(saved.getId(), ORGANIZER, null))
                .assertNext(updated -> assertThat(updated.getImage()).isNull())
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void setImage_shouldBeEmpty_forAnUnknownEvent() {
        StepVerifier.create(adapter.setImage("64b7f9f2e4b0a1c2d3e4f5a6", ORGANIZER, "events/x/cover-1.jpg")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("only the organizer rewrites the details; a new title marks the group for wely-chat")
    void updateDetails_shouldRewriteTheDetails_andBumpTheGroupOnlyOnARename() {
        Event saved = anEvent("Standup");
        EventDetails details = new EventDetails("Standup", "Ninkasi", new GeoPoint(45.73, 4.83), "Bring snacks", Visibility.PRIVATE);
        Long versionBefore = stored(saved.getId()).getGroupVersion();

        StepVerifier.create(adapter.updateDetails(saved.getId(), "someone-else", details, false)).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.updateDetails(saved.getId(), ORGANIZER, details, false))
                .assertNext(updated -> {
                    assertThat(updated.getLocation()).isEqualTo("Ninkasi");
                    assertThat(updated.getCoordinates()).isEqualTo(new GeoPoint(45.73, 4.83));
                    assertThat(updated.getDescription()).isEqualTo("Bring snacks");
                    assertThat(updated.getVisibility()).isEqualTo(Visibility.PRIVATE);
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(stored(saved.getId()).getGroupVersion()).isEqualTo(versionBefore);

        StepVerifier.create(adapter.updateDetails(saved.getId(), ORGANIZER,
                        new EventDetails("Daily", "Ninkasi", null, null, Visibility.PUBLIC), true))
                .assertNext(updated -> {
                    assertThat(updated.getTitle()).isEqualTo("Daily");
                    assertThat(updated.getCoordinates()).isNull();
                })
                .expectComplete().verify(Duration.ofSeconds(10));
        EventEntity renamed = stored(saved.getId());
        assertThat(renamed.getGroupVersion()).isEqualTo(versionBefore == null ? 1L : versionBefore + 1);
        assertThat(renamed.getGroupSynced()).isFalse();
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
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(stored(saved.getId()).getDateFixSynced()).isNull();
    }

    @Test
    void castBallot_shouldReplaceTheVotersWholeBallot() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES, "fri", PollAnswer.NO)).block();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of("fri", PollAnswer.MAYBE)))
                .assertNext(updated -> assertThat(updated.getPoll().ballots())
                        .containsExactly(Map.entry("lea", Map.of("fri", PollAnswer.MAYBE))))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void castBallot_shouldNotLetTwoVotersOverwriteEachOther() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();

        StepVerifier.create(Flux.range(1, 20)
                        .flatMap(i -> adapter.castBallot(id, "voter-" + i, Map.of("thu", PollAnswer.YES)), 10))
                .expectNextCount(20)
                .expectComplete().verify(Duration.ofSeconds(10));

        StepVerifier.create(adapter.findById(id))
                .assertNext(found -> {
                    assertThat(found.getPoll().ballots()).hasSize(20);
                    assertThat(found.getPoll().yesVotersOf("thu")).hasSize(20);
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void castBallot_shouldAnswerTheEvent_whenTheSameBallotIsCastTwice() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES)).block();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES)))
                .assertNext(updated -> assertThat(updated.getPoll().yesVotersOf("thu")).containsExactly("lea"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void castBallot_shouldWithdrawTheBallot_whenGivenNoAnswer() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES)).block();
        adapter.castBallot(id, "hugo", Map.of("thu", PollAnswer.NO)).block();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of()))
                .assertNext(updated -> assertThat(updated.getPoll().ballots()).containsOnlyKeys("hugo"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void castBallot_shouldBeEmpty_onceTheDateIsFixed_orWithoutPoll() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.fixPollDate(id, ORGANIZER, THURSDAY, List.of()).block();
        String withoutPoll = anEvent("Standup").getId();

        StepVerifier.create(adapter.castBallot(id, "lea", Map.of("thu", PollAnswer.YES))).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.castBallot(withoutPoll, "lea", Map.of("thu", PollAnswer.YES))).expectComplete().verify(Duration.ofSeconds(10));
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
                .expectComplete().verify(Duration.ofSeconds(10));
        EventEntity stored = stored(id);
        assertThat(stored.getGroupVersion()).isEqualTo(4L);
        assertThat(stored.getGroupSynced()).isFalse();
        assertThat(stored.getDateFixSynced()).isFalse();
    }

    @Test
    void fixPollDate_shouldMatchOnlyOnce() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();
        adapter.fixPollDate(id, ORGANIZER, THURSDAY, List.of()).block();

        StepVerifier.create(adapter.fixPollDate(id, ORGANIZER, FRIDAY, List.of("lea"))).expectComplete().verify(Duration.ofSeconds(10));
        Event found = adapter.findById(id).block();
        assertThat(found.getPoll().fixedSlotId()).isEqualTo("thu");
        assertThat(found.getParticipantIds()).isEmpty();
        assertThat(stored(id).getGroupVersion()).isEqualTo(1L);
    }

    @Test
    void fixPollDate_shouldBeEmpty_forSomeoneOtherThanTheOrganizer() {
        String id = aPoll("Dinner", ORGANIZER, List.of()).getId();

        StepVerifier.create(adapter.fixPollDate(id, "lea", FRIDAY, List.of())).expectComplete().verify(Duration.ofSeconds(10));
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
                .expectComplete().verify(Duration.ofSeconds(10));
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
                THURSDAY.end().plusSeconds(3600)), false)).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(read.getId()).block().getStartDate()).isEqualTo(FRIDAY.start());
    }

    @Test
    void reschedule_shouldBeEmpty_forAnOpenPollOrSomeoneElse() {
        Event poll = aPoll("Dinner", ORGANIZER, List.of());
        Event plain = saveAt("Bowling", ORGANIZER, List.of(), "2026-10-16T17:00:00Z", "2026-10-16T19:00:00Z");
        Event notMine = Event.builder().id(plain.getId()).organizerId("lea").startDate(plain.getStartDate()).build();

        StepVerifier.create(adapter.reschedule(poll, new TimeRange(FRIDAY.start(), FRIDAY.end()), false)).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.reschedule(notMine, new TimeRange(FRIDAY.start(), FRIDAY.end()), false)).expectComplete().verify(Duration.ofSeconds(10));
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

        StepVerifier.create(adapter.countPastInvolvingBoth("lea", "hugo", OVERVIEW_NOW)).expectNext(8L).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.countPastInvolvingBoth("lea", "nobody", OVERVIEW_NOW)).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a friend's upcoming events are those they organize or join and that are not over, soonest first")
    void findUpcomingInvolving_shouldKeepTheUsersEventsNotOverYet_soonestFirst() {
        saveAt("hugo joins, later", "zoe", List.of("hugo"), "2026-10-25T10:00:00Z", "2026-10-25T11:00:00Z");
        saveAt("hugo organizes, tomorrow", "hugo", List.of(), "2026-10-15T10:00:00Z", null);
        saveAt("hugo, over", "hugo", List.of(), "2026-10-13T10:00:00Z", "2026-10-13T11:00:00Z");
        saveAt("someone else's", "zoe", List.of("lea"), "2026-10-16T10:00:00Z", null);

        assertThat(titles(adapter.findUpcomingInvolving("hugo", "nobody", OVERVIEW_NOW)))
                .containsExactly("hugo organizes, tomorrow", "hugo joins, later");
    }

    // --- Communities ------------------------------------------------------------------------------

    private Event aCommunityEvent(String title, String communityId, Instant start, Instant end) {
        List<String> going = new ArrayList<>(List.of(ORGANIZER));
        return adapter.save(Event.builder().title(title).organizerId(ORGANIZER).startDate(start).endDate(end)
                .location("Gerland").participantIds(going).visibility(Visibility.COMMUNITY).communityId(communityId)
                .build()).block();
    }

    @Test
    @DisplayName("a community's upcoming events, soonest first, an event without an end lasting an hour")
    void findUpcomingInCommunity_shouldListWhatIsNotOverYet() {
        Instant now = Instant.parse("2026-10-15T10:00:00Z");
        aCommunityEvent("later", "c1", now.plusSeconds(7200), null);
        aCommunityEvent("soon", "c1", now.plusSeconds(600), now.plusSeconds(3600));
        aCommunityEvent("started half an hour ago, no end", "c1", now.minusSeconds(1800), null);
        aCommunityEvent("over", "c1", now.minusSeconds(7200), now.minusSeconds(3600));
        aCommunityEvent("elsewhere", "c2", now.plusSeconds(600), null);

        assertThat(adapter.findUpcomingInCommunity("c1", "nobody", now).map(Event::getTitle).collectList().block())
                .containsExactly("started half an hour ago, no end", "soon", "later");
    }

    @Test
    @DisplayName("answers move a member in and out of the participants, keeping maybe and no, and never the organizer")
    void respond_shouldKeepParticipantsAndAnswersInStep() {
        Event event = aCommunityEvent("Balade", "c1", Instant.parse("2027-01-01T10:00:00Z"), null);
        String id = event.getId();

        Event yes = adapter.respond(id, "lea", PollAnswer.YES).block();
        assertThat(yes.getParticipantIds()).containsExactly(ORGANIZER, "lea");

        Event maybe = adapter.respond(id, "lea", PollAnswer.MAYBE).block();
        assertThat(maybe.getParticipantIds()).containsExactly(ORGANIZER);
        assertThat(maybe.getResponses()).containsEntry("lea", PollAnswer.MAYBE);

        Event no = adapter.respond(id, "lea", PollAnswer.NO).block();
        assertThat(no.getResponses()).containsEntry("lea", PollAnswer.NO);

        Event back = adapter.respond(id, "lea", PollAnswer.YES).block();
        assertThat(back.getParticipantIds()).containsExactly(ORGANIZER, "lea");
        assertThat(back.getResponses()).doesNotContainKey("lea");

        StepVerifier.create(adapter.respond(id, ORGANIZER, PollAnswer.NO)).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(id).block().getParticipantIds()).contains(ORGANIZER);
    }

    @Test
    void clearUpcomingResponses_shouldUnsetOnlyUpcomingCommunityAnswers() {
        String upcoming = aCommunityEvent("upcoming", "c1", DELETED_AT.plusSeconds(3600), null).getId();
        String past = aCommunityEvent("past", "c1", DELETED_AT.minusSeconds(7200), DELETED_AT.minusSeconds(3600)).getId();
        adapter.respond(upcoming, "gone", PollAnswer.MAYBE).block();
        adapter.respond(upcoming, "lea", PollAnswer.NO).block();
        adapter.respond(past, "gone", PollAnswer.NO).block();
        Long version = stored(upcoming).getGroupVersion();

        StepVerifier.create(adapter.clearUpcomingResponses("gone", DELETED_AT)).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(adapter.findById(upcoming).block().getResponses()).containsOnlyKeys("lea");
        assertThat(adapter.findById(past).block().getResponses()).containsEntry("gone", PollAnswer.NO);
        assertThat(stored(upcoming).getGroupVersion()).isEqualTo(version);
        StepVerifier.create(adapter.clearUpcomingResponses("gone", DELETED_AT)).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("answering an event outside any community writes nothing")
    void respond_shouldIgnoreAnEventOutsideAnyCommunity() {
        Event event = anEvent("Standup");

        StepVerifier.create(adapter.respond(event.getId(), "lea", PollAnswer.YES)).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findById(event.getId()).block().getParticipantIds()).doesNotContain("lea");
    }

    // --- Birthdays and surprises: kept from the honoree, and out of every calendar read ----------

    @Autowired
    private org.springframework.data.mongodb.core.mapping.MongoMappingContext mappingContext;

    private static final Instant PARTY = MAP_NOW.plus(java.time.Duration.ofDays(9));

    /** Bob organizes Léa's surprise; Hugo is Léa's friend, Zoé only Bob's. */
    private Event leasSurprise() {
        return adapter.save(Event.builder()
                .title("Léa's surprise")
                .organizerId("bob")
                .kind(com.calendar.events.domain.models.EventKind.SURPRISE)
                .honoreeId("lea")
                .hiddenFrom(new ArrayList<>(List.of("lea")))
                .visibility(Visibility.FRIENDS)
                .startDate(PARTY)
                .endDate(PARTY.plusSeconds(4 * 3600))
                .coordinates(new GeoPoint(45.764, 4.8357))
                .occurrenceKey("lea:2026")
                .participantIds(new ArrayList<>(List.of("bob")))
                .build()).block();
    }

    private Event birthdayOf(String userId, String name) {
        return adapter.upsertBirthday(com.calendar.events.domain.services.BirthdayService
                .birthdayEvent(userId, name, java.time.MonthDay.of(10, 20))).block();
    }

    @Test
    @DisplayName("a surprise is in its honoree's friends' feed and map, never in the honoree's nor a stranger's")
    void findFeedAndFindOnMap_shouldKeepASurpriseFromItsHonoree() {
        leasSurprise();

        assertThat(adapter.findFeed("hugo", List.of("lea")).map(Event::getTitle).collectList().block())
                .containsExactly("Léa's surprise");
        assertThat(adapter.findFeed("lea", List.of("bob", "hugo")).collectList().block()).isEmpty();
        assertThat(adapter.findFeed("zoe", List.of("bob")).collectList().block()).isEmpty();
        assertThat(adapter.findOnMap("hugo", List.of("lea"), LYON, MAP_NOW, 10).collectList().block()).hasSize(1);
        assertThat(adapter.findOnMap("lea", List.of("bob"), LYON, MAP_NOW, 10).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("the honoree cannot reach a surprise by id, nor see it busy in a calendar")
    void findShownToAndFindOccupying_shouldKeepASurpriseFromItsHonoree() {
        Event surprise = leasSurprise();
        Instant from = PARTY.minusSeconds(3600);
        Instant to = PARTY.plusSeconds(86_400);

        StepVerifier.create(adapter.findShownTo(surprise.getId(), "lea")).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findShownTo(surprise.getId(), "hugo")).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findOccupying(List.of("bob"), from, to, "lea").collectList().block()).isEmpty();
        assertThat(adapter.findOccupying(List.of("bob"), from, to, "hugo").collectList().block()).hasSize(1);
        assertThat(adapter.findUpcomingInvolving("bob", "lea", MAP_NOW).collectList().block()).isEmpty();
        assertThat(adapter.findUpcomingInvolving("bob", "hugo", MAP_NOW).collectList().block()).hasSize(1);
    }

    @Test
    @DisplayName("an event hidden from a member stays out of their community's listing")
    void findUpcomingInCommunity_shouldLeaveOutWhatIsHiddenFromTheViewer() {
        Event hidden = aCommunityEvent("hidden", "c1", MAP_NOW.plusSeconds(3600), null);
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(hidden.getId())),
                new Update().set("hiddenFrom", List.of("lea")), "events").block();

        assertThat(adapter.findUpcomingInCommunity("c1", "lea", MAP_NOW).collectList().block()).isEmpty();
        assertThat(adapter.findUpcomingInCommunity("c1", "hugo", MAP_NOW).collectList().block()).hasSize(1);
    }

    @Test
    @DisplayName("a birthday is one document per user, rewritten in place")
    void upsertBirthday_shouldKeepOneBirthdayPerUser() {
        birthdayOf("lea", "Léa");
        birthdayOf("lea", "Léa M.");
        birthdayOf("bob", "Bob");

        assertThat(adapter.findBirthdaysOf(List.of("lea")).map(Event::getHonoreeName).collectList().block())
                .containsExactly("Léa M.");
        StepVerifier.create(adapter.findBirthdayOf("lea"))
                .assertNext(birthday -> assertThat(birthday.getId()).isEqualTo("birthday-lea"))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findAllBirthdays().collectList().block()).hasSize(2);
        StepVerifier.create(adapter.deleteBirthdayOf("lea")).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findBirthdayOf("lea")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a birthday is served by none of the calendar reads, even to its own user")
    void calendarReads_shouldLeaveBirthdaysOut() {
        Event birthday = birthdayOf("lea", "Léa");
        Instant from = Instant.parse("2000-10-19T00:00:00Z");
        Instant to = Instant.parse("2000-10-22T00:00:00Z");

        assertThat(adapter.findFeed("hugo", List.of("lea")).collectList().block()).isEmpty();
        assertThat(adapter.findFeed("lea", List.of()).collectList().block()).isEmpty();
        StepVerifier.create(adapter.findShownTo(birthday.getId(), "lea")).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findOccupying(List.of("lea"), from, to, "hugo").collectList().block()).isEmpty();
        assertThat(adapter.findInvolving("lea").collectList().block()).isEmpty();
        assertThat(adapter.findByParticipantId("lea").collectList().block()).isEmpty();
        assertThat(adapter.findUpcomingInvolving("lea", "hugo", from).collectList().block()).isEmpty();
    }

    @Test
    @DisplayName("a second surprise for the same birthday is refused by the unique occurrence key")
    void insertSurprise_shouldRefuseASecondSurpriseForTheSameBirthday() {
        org.springframework.data.mongodb.core.index.IndexResolver.create(mappingContext).resolveIndexFor(EventEntity.class)
                .forEach(index -> mongoTemplate.indexOps(EventEntity.class).createIndex(index).block());
        Event first = Event.builder().title("first").organizerId("bob").kind(com.calendar.events.domain.models.EventKind.SURPRISE)
                .honoreeId("lea").hiddenFrom(new ArrayList<>(List.of("lea"))).occurrenceKey("lea:2027")
                .startDate(PARTY).participantIds(new ArrayList<>(List.of("bob"))).build();
        Event second = Event.builder().title("second").organizerId("zoe").kind(com.calendar.events.domain.models.EventKind.SURPRISE)
                .honoreeId("lea").hiddenFrom(new ArrayList<>(List.of("lea"))).occurrenceKey("lea:2027")
                .startDate(PARTY.plusSeconds(3600)).participantIds(new ArrayList<>(List.of("zoe"))).build();

        StepVerifier.create(adapter.insertSurprise(first)).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.insertSurprise(second)).expectComplete().verify(Duration.ofSeconds(10));
        // A standard event has no occurrence key: the sparse index lets any number of them in.
        anEvent("one");
        anEvent("two");

        StepVerifier.create(adapter.findSurpriseByOccurrence("lea:2027"))
                .assertNext(found -> assertThat(found.getTitle()).isEqualTo("first"))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("surprises are found by birthday, by date and by honoree, the listing hiding them from the honoree")
    void surpriseQueries_shouldFindTheSurprise() {
        leasSurprise();

        assertThat(adapter.findSurprisesFor(List.of("lea:2026"), "hugo").collectList().block()).hasSize(1);
        assertThat(adapter.findSurprisesFor(List.of("lea:2026"), "lea").collectList().block()).isEmpty();
        StepVerifier.create(adapter.findSurpriseStartingBetween("lea", PARTY.minusSeconds(60), PARTY.plusSeconds(60)))
                .expectNextCount(1).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(adapter.findSurpriseStartingBetween("lea", PARTY.plusSeconds(60), PARTY.plusSeconds(120)))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(adapter.findSurprisesHonoring("lea").collectList().block()).hasSize(1);
        assertThat(adapter.findSurprisesHonoring("bob").collectList().block()).isEmpty();
    }

    // --- Circle calendar ------------------------------------------------------

    @Test
    @DisplayName("the circle reads every intimate friend's busy events at once, but never one hidden from the viewer")
    void findOccupying_shouldServeTheCircle_withoutWhatIsHiddenFromTheViewer() {
        saveAt("bob's dinner", "bob", List.of("bob"), "2026-10-17T18:00:00Z", "2026-10-17T20:00:00Z");
        saveAt("zoe joins", "max", List.of("max", "zoe"), "2026-10-18T18:00:00Z", "2026-10-18T20:00:00Z");
        saveAt("max alone", "max", List.of("max"), "2026-10-18T10:00:00Z", "2026-10-18T11:00:00Z");
        Event secret = saveAt("lea's surprise", "bob", List.of("bob", "zoe"), "2026-10-19T18:00:00Z", "2026-10-19T20:00:00Z");
        secret.setKind(com.calendar.events.domain.models.EventKind.SURPRISE);
        secret.setHonoreeId("lea");
        secret.setHiddenFrom(new ArrayList<>(List.of("lea")));
        adapter.save(secret).block();

        assertThat(adapter.findOccupying(List.of("bob", "zoe"), Instant.parse("2026-10-17T00:00:00Z"),
                        Instant.parse("2026-10-20T00:00:00Z"), "lea").map(Event::getTitle).collectList().block())
                .containsExactlyInAnyOrder("bob's dinner", "zoe joins");
    }

    // --- Shared outings, for reunions and memories ------------------------------

    private static final Instant SHARED_NOW = Instant.parse("2026-10-18T08:00:00Z");

    private Event withKind(Event event, com.calendar.events.domain.models.EventKind kind, String honoree) {
        event.setKind(kind);
        event.setHonoreeId(honoree);
        event.setHiddenFrom(new ArrayList<>(honoree == null ? List.of() : List.of(honoree)));
        return adapter.save(event).block();
    }

    @Test
    @DisplayName("a shared outing has started with both on it, most recent first; never a surprise for either, nor a poll")
    void findSharedOutings_shouldKeepStartedEventsBothAreOn_mostRecentFirst() {
        saveAt("older", "lea", List.of("lea", "hugo"), "2026-03-01T18:00:00Z", "2026-03-01T20:00:00Z");
        saveAt("newer, hugo organizes", "hugo", List.of("hugo", "zoe", "lea"), "2026-09-01T18:00:00Z", null);
        saveAt("still running", "zoe", List.of("zoe", "lea", "hugo"), "2026-10-18T07:30:00Z", "2026-10-18T09:00:00Z");
        saveAt("not started", "lea", List.of("lea", "hugo"), "2026-10-20T18:00:00Z", "2026-10-20T20:00:00Z");
        saveAt("lea only", "lea", List.of("lea", "zoe"), "2026-05-01T18:00:00Z", "2026-05-01T20:00:00Z");
        withKind(saveAt("lea's surprise", "hugo", List.of("hugo", "zoe"), "2026-04-01T18:00:00Z", null),
                com.calendar.events.domain.models.EventKind.SURPRISE, "lea");
        withKind(saveAt("zoe's surprise", "hugo", List.of("hugo", "lea"), "2026-04-02T18:00:00Z", null),
                com.calendar.events.domain.models.EventKind.SURPRISE, "zoe");
        Event poll = aPoll("polling", "lea", List.of("lea", "hugo"));
        poll.setStartDate(Instant.parse("2026-01-01T10:00:00Z"));
        adapter.save(poll).block();

        assertThat(titles(adapter.findSharedOutings("hugo", "lea", SHARED_NOW)))
                .containsExactly("still running", "newer, hugo organizes", "zoe's surprise", "older");
        assertThat(adapter.findLastSharedOuting("lea", "hugo", SHARED_NOW).map(Event::getTitle).block())
                .isEqualTo("still running");
        StepVerifier.create(adapter.findLastSharedOuting("lea", "max", SHARED_NOW)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("something upcoming together: not started, or a date still being voted on; never a surprise for either")
    void hasUpcomingShared_shouldFindAnEventNotStartedYet_orStillPolling() {
        saveAt("past", "lea", List.of("lea", "hugo"), "2026-03-01T18:00:00Z", "2026-03-01T20:00:00Z");
        withKind(saveAt("hugo's surprise", "lea", List.of("lea", "zoe"), "2026-11-01T18:00:00Z", null),
                com.calendar.events.domain.models.EventKind.SURPRISE, "hugo");
        assertThat(adapter.hasUpcomingShared("lea", "hugo", SHARED_NOW).block()).isFalse();

        Event poll = aPoll("polling", "lea", List.of("lea", "hugo"));
        poll.setStartDate(Instant.parse("2026-01-01T10:00:00Z"));
        adapter.save(poll).block();
        assertThat(adapter.hasUpcomingShared("hugo", "lea", SHARED_NOW).block()).isTrue();

        saveAt("next week", "zoe", List.of("zoe", "max", "lea"), "2026-10-25T18:00:00Z", null);
        assertThat(adapter.hasUpcomingShared("max", "lea", SHARED_NOW).block()).isTrue();
        assertThat(adapter.hasUpcomingShared("max", "hugo", SHARED_NOW).block()).isFalse();
    }
}
