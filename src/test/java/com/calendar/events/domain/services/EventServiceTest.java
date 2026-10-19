package com.calendar.events.domain.services;

import java.util.Map;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FeedRank;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.EventDetails;
import com.calendar.events.domain.models.CommunityRole;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.LeaveOutcome;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.models.StoredMedia;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.CommunityDirectory;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SocialGraph socialGraph;

    @Mock
    private ExpenseRepository expenseRepository;

    @Mock
    private MediaStore mediaStore;

    @Mock
    private EventDeletionOutbox deletionOutbox;

    @Mock
    private CommunityDirectory communityDirectory;

    @Mock
    private DeletedUserMarkers markers;

    private EventService eventService;

    private static final Instant NOW = Instant.parse("2026-10-11T16:00:00Z");
    private static final GeoBox LYON = new GeoBox(45.70, 4.77, 45.81, 4.92);

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, socialGraph, expenseRepository, new PictureService(mediaStore),
                deletionOutbox, communityDirectory, new AccountGuard(markers), Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(deletionOutbox.record(any())).thenReturn(Mono.empty());
        lenient().when(markers.deletedAmong(any())).thenReturn(Mono.just(Set.of()));
        // The birthday and hidden-from filter is the database's (see the adapter's integration test):
        // here an event reached by an API caller is whatever findById holds.
        lenient().when(eventRepository.findShownTo(anyString(), anyString()))
                .thenAnswer(call -> eventRepository.findById(call.<String>getArgument(0)));
    }

    @Test
    void getAllEvents_shouldReturnFluxOfEvents() {
        Event event = Event.builder().id("1").title("Test Event").build();
        when(eventRepository.findAll()).thenReturn(Flux.just(event));

        Flux<Event> result = eventService.getAllEvents();

        StepVerifier.create(result)
                .expectNext(event)
                .verifyComplete();
    }

    private static Event by(String organizer, Visibility visibility) {
        return Event.builder().id("1").organizerId(organizer).visibility(visibility).participantIds(new ArrayList<>()).build();
    }

    private static boolean hasCode(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    @Test
    void getEventById_shouldReturnAPublicEvent_withoutAskingForFriends() {
        Event event = by("carol", Visibility.PUBLIC);
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectNext(event)
                .verifyComplete();
        verifyNoInteractions(socialGraph);
    }

    @Test
    void getEventById_shouldReturnAFriendsEvent_whenTheOrganizerIsAFriend() {
        Event event = by("bob", Visibility.FRIENDS);
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of("bob")));

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getEventById_shouldAnswerNotFound_whenTheOrganizerIsNotAFriend() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("carol", Visibility.FRIENDS)));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of("bob")));

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void getEventById_shouldAnswerNotFound_forSomeoneElsesPrivateEvent() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("bob", Visibility.PRIVATE)));

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verifyNoInteractions(socialGraph);
    }

    @Test
    void getEventById_shouldReturnAPrivateEvent_toItsOrganizer() {
        Event event = by("alice", Visibility.PRIVATE);
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getEventById_shouldAnswerNotFound_whenMissing() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.getEventById("missing", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    private static Event withParticipants(String organizer, Visibility visibility, String... participants) {
        Event event = by(organizer, visibility);
        event.getParticipantIds().addAll(List.of(participants));
        return event;
    }

    @Test
    void getParticipants_shouldListEveryoneAndNameFriends_forAParticipant() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(withParticipants("carol", Visibility.PRIVATE, "alice", "bob", "dave")));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of("dave")));

        StepVerifier.create(eventService.getParticipants("1", "alice"))
                .expectNext(new Participants(3, List.of("alice", "bob", "dave"), List.of("dave")))
                .verifyComplete();
    }

    @Test
    void getParticipants_shouldOnlyCountAndNameFriends_forSomeoneNotInvolved() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(withParticipants("carol", Visibility.PUBLIC, "alice", "bob")));
        when(socialGraph.friendIdsOf("erin")).thenReturn(Mono.just(Set.of("bob")));

        StepVerifier.create(eventService.getParticipants("1", "erin"))
                .expectNext(new Participants(2, List.of(), List.of("bob")))
                .verifyComplete();
    }

    @Test
    void getParticipants_shouldNotHideOtherFailures() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(withParticipants("carol", Visibility.PUBLIC, "alice")));
        when(socialGraph.friendIdsOf("carol")).thenReturn(Mono.error(new IllegalStateException("boom")));

        StepVerifier.create(eventService.getParticipants("1", "carol"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void getParticipants_shouldAnswerNotFound_forAnEventTheViewerCannotSee() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(withParticipants("carol", Visibility.PRIVATE, "alice")));

        StepVerifier.create(eventService.getParticipants("1", "erin"))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void getParticipants_shouldAnswerNotFound_whenMissing() {
        when(eventRepository.findById("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.getParticipants("1", "erin"))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void createEvent_shouldDefaultTheVisibilityToFriends() {
        Event event = Event.builder().title("New Event").build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event).block();

        org.assertj.core.api.Assertions.assertThat(event.getVisibility()).isEqualTo(Visibility.FRIENDS);
    }

    @Test
    void createEvent_shouldKeepAChosenVisibility() {
        Event event = Event.builder().title("New Event").visibility(Visibility.PRIVATE).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event).block();

        org.assertj.core.api.Assertions.assertThat(event.getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void createEvent_shouldIgnoreAnyCover_whichIsOnlyEverUploaded() {
        Event event = Event.builder().title("New Event").image("https://tracker.example/pixel.gif").build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event).block();

        assertThat(event.getImage()).isNull();
    }

    @Test
    void createEvent_shouldReturnSavedEvent() {
        Event event = Event.builder().title("New Event").build();
        Event savedEvent = Event.builder().id("1").title("New Event").build();
        when(eventRepository.save(event)).thenReturn(Mono.just(savedEvent));

        Mono<Event> result = eventService.createEvent(event);

        StepVerifier.create(result)
                .expectNext(savedEvent)
                .verifyComplete();
    }

    private static PollSlot candidate(String start, String end) {
        return new PollSlot(null, Instant.parse(start), Instant.parse(end));
    }

    @Test
    void createEvent_shouldOpenAPollAndSitOnTheEarliestSlot_whenGivenCandidateSlots() {
        Event event = Event.builder().title("Dinner").startDate(NOW).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.createEvent(event, List.of(
                        candidate("2026-10-17T18:00:00Z", "2026-10-17T21:00:00Z"),
                        candidate("2026-10-16T18:00:00Z", "2026-10-16T21:00:00Z"))))
                .expectNext(event)
                .verifyComplete();

        assertThat(event.isPolling()).isTrue();
        assertThat(event.getPoll().slots()).hasSize(2);
        assertThat(event.getStartDate()).isEqualTo(Instant.parse("2026-10-16T18:00:00Z"));
        assertThat(event.getEndDate()).isEqualTo(Instant.parse("2026-10-16T21:00:00Z"));
    }

    @Test
    void createEvent_shouldRejectInvalidCandidates_withoutSaving() {
        Event event = Event.builder().title("Dinner").build();

        StepVerifier.create(eventService.createEvent(event, List.of(
                        candidate("2026-10-17T18:00:00Z", "2026-10-17T21:00:00Z"))))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.POLL_INVALID))
                .verify();
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_shouldRejectACandidateInThePast() {
        Event event = Event.builder().title("Dinner").build();

        StepVerifier.create(eventService.createEvent(event, List.of(
                        candidate("2026-10-10T18:00:00Z", "2026-10-10T21:00:00Z"),
                        candidate("2026-10-17T18:00:00Z", "2026-10-17T21:00:00Z"))))
                .expectErrorMatches(error -> hasCode(error, EventErrorCode.POLL_INVALID))
                .verify();
    }

    @Test
    void createEvent_shouldIgnoreAnyPollSent_whenGivenNoCandidates() {
        Event event = Event.builder().title("Dinner").poll(new Poll(PollStatus.CLOSED, List.of(), null, "x")).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event, null).block();

        assertThat(event.getPoll()).isNull();
    }

    @Test
    void deleteEvent_shouldDeleteWhenRequesterIsOrganizer() {
        Event event = Event.builder().id("1").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.just(event));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer"))
                .verifyComplete();
        verify(eventRepository, times(1)).deleteByOrganizer("1", "organizer");
    }

    @Test
    void deleteEvent_shouldTakeItsExpensesWithIt() {
        Event event = Event.builder().id("1").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.just(event));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();

        verify(expenseRepository).deleteByEvent("1");
    }

    @Test
    void deleteEvent_shouldKeepTheExpenses_whenTheEventCannotBeDeleted() {
        Event event = Event.builder().id("1").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyError(IllegalStateException.class);

        verify(expenseRepository, never()).deleteByEvent(any());
    }

    @Test
    void deleteEvent_shouldRejectWhenRequesterIsNotOrganizer() {
        Event event = Event.builder().id("1").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.deleteEvent("1", "someone-else"))
                .expectErrorMatches(e -> e instanceof EventException
                        && ((EventException) e).getErrorCode() == EventErrorCode.EVENT_ACCESS_DENIED)
                .verify();
        verify(eventRepository, never()).deleteByOrganizer(any(), any());
    }

    @Test
    void deleteEvent_shouldFailWhenEventDoesNotExist() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("missing", "organizer"))
                .expectErrorMatches(e -> e instanceof EventException
                        && ((EventException) e).getErrorCode() == EventErrorCode.EVENT_NOT_FOUND)
                .verify();
        verify(eventRepository, never()).deleteByOrganizer(any(), any());
    }

    @Test
    void toggleSubscription_shouldSubscribeWhenNotParticipant() {
        Event event = Event.builder().id("1").organizerId("carol").visibility(Visibility.PUBLIC)
                .participantIds(new ArrayList<>()).build();
        Event subscribed = Event.builder().id("1").participantIds(List.of("user123")).build();

        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.addParticipant("1", "user123")).thenReturn(Mono.just(subscribed));

        StepVerifier.create(eventService.toggleSubscription("1", "user123"))
                .expectNextMatches(evt -> evt.getParticipantIds().contains("user123"))
                .verifyComplete();

        verify(eventRepository).addParticipant("1", "user123");
        verify(eventRepository, never()).removeParticipant(any(), any());
        // No save: rewriting the document would lose a concurrent subscription.
        verify(eventRepository, never()).save(any());
    }

    @Test
    void toggleSubscription_shouldRefuseToJoinAnEventTheUserCannotSee() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("carol", Visibility.FRIENDS)));
        when(socialGraph.friendIdsOf("user123")).thenReturn(Mono.just(Set.of()));

        StepVerifier.create(eventService.toggleSubscription("1", "user123"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(eventRepository, never()).addParticipant(any(), any());
    }

    @Test
    void toggleSubscription_shouldLetAParticipantLeaveAPrivateEvent() {
        Event event = by("carol", Visibility.PRIVATE);
        event.getParticipantIds().add("user123");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.removeParticipant("1", "user123")).thenReturn(Mono.just(by("carol", Visibility.PRIVATE)));

        StepVerifier.create(eventService.toggleSubscription("1", "user123"))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void toggleSubscription_shouldUnsubscribeWhenAlreadyParticipant() {
        Event event = Event.builder().id("1").participantIds(new ArrayList<>(List.of("user123"))).build();
        Event unsubscribed = Event.builder().id("1").participantIds(new ArrayList<>()).build();

        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.removeParticipant("1", "user123")).thenReturn(Mono.just(unsubscribed));

        StepVerifier.create(eventService.toggleSubscription("1", "user123"))
                .expectNextMatches(evt -> !evt.getParticipantIds().contains("user123"))
                .verifyComplete();

        verify(eventRepository).removeParticipant("1", "user123");
        verify(eventRepository, never()).addParticipant(any(), any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void toggleSubscription_shouldFailWhenEventDoesNotExist() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.toggleSubscription("missing", "user123"))
                .expectErrorMatches(e -> e instanceof EventException
                        && ((EventException) e).getErrorCode() == EventErrorCode.EVENT_NOT_FOUND)
                .verify();
    }

    @Test
    void getSubscribedEvents_shouldReturnSubscribedEvents() {
        Event event = Event.builder().id("1").build();
        when(eventRepository.findByParticipantId("user123")).thenReturn(Flux.just(event));

        Flux<Event> result = eventService.getSubscribedEvents("user123");

        StepVerifier.create(result)
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getFeedEvents_shouldPassTheUsersFriends_inTheStoredOrder() {
        Event later = Event.builder().id("1").organizerId("bob").startDate(Instant.parse("2026-11-02T10:00:00Z")).build();
        Event sooner = Event.builder().id("2").organizerId("ana").startDate(Instant.parse("2026-11-01T10:00:00Z")).build();
        ViewerFriends friends = new ViewerFriends(Map.of("bob", Closeness.INTIMATE));
        when(eventRepository.findFeed("user123", Set.of("bob"))).thenReturn(Flux.just(later, sooner));

        StepVerifier.create(eventService.getFeedEvents("user123", friends, FeedRank.DEFAULT))
                .expectNext(later, sooner)
                .verifyComplete();
    }

    @Test
    void getFeedEvents_shouldPutTheClosestFriendsEventsFirst_thenTheSoonest_whenRankedByAffinity() {
        Instant day = Instant.parse("2026-11-01T10:00:00Z");
        Event withNoFriend = Event.builder().id("none").organizerId("x").startDate(day).build();
        Event withAFriend = Event.builder().id("friend").organizerId("ana").startDate(day.plusSeconds(60)).build();
        Event withAnIntimateLater = Event.builder().id("intimate-later").organizerId("x")
                .participantIds(new ArrayList<>(List.of("bob"))).startDate(day.plusSeconds(7200)).build();
        Event withAnIntimateSooner = Event.builder().id("intimate-sooner").organizerId("bob").startDate(day.plusSeconds(3600)).build();
        ViewerFriends friends = new ViewerFriends(Map.of("ana", Closeness.FRIEND, "bob", Closeness.INTIMATE));
        when(eventRepository.findFeed(eq("user123"), any()))
                .thenReturn(Flux.just(withNoFriend, withAFriend, withAnIntimateLater, withAnIntimateSooner));

        StepVerifier.create(eventService.getFeedEvents("user123", friends, FeedRank.AFFINITY).map(Event::getId))
                .expectNext("intimate-sooner", "intimate-later", "friend", "none")
                .verifyComplete();
    }

    @Test
    void getFeedEvents_shouldPassAReadFailureOn() {
        when(eventRepository.findFeed("user123", Set.of())).thenReturn(Flux.error(new IllegalStateException("mongo down")));

        StepVerifier.create(eventService.getFeedEvents("user123", ViewerFriends.NONE, FeedRank.AFFINITY))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void getMapEvents_shouldAskForWhatIsVisibleInTheAreaFromNowOn() {
        Event event = Event.builder().id("2").build();
        when(socialGraph.friendIdsOf("user123")).thenReturn(Mono.just(Set.of("bob")));
        when(eventRepository.findOnMap("user123", Set.of("bob"), LYON, NOW, EventService.MAP_LIMIT)).thenReturn(Flux.just(event));

        StepVerifier.create(eventService.getMapEvents("user123", LYON))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getMapEvents_shouldNotHideOtherFailures() {
        when(socialGraph.friendIdsOf("user123")).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(eventService.getMapEvents("user123", LYON))
                .expectError(IllegalStateException.class)
                .verify();
    }

    private static Expense expenseWithReceipt(String id, String receiptKey) {
        return new Expense(id, "1", "Courses", 1000, "EUR", "organizer", "organizer", ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share("organizer", 0)), NOW, receiptKey, null, 0L);
    }

    @Test
    void deleteEvent_shouldDiscardTheCoverAndEveryReceipt() {
        Event event = Event.builder().id("1").organizerId("organizer").image("events/1/cover-a.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.just(event));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.just(expenseWithReceipt("x1", "events/1/receipts/x1-a.jpg"),
                expenseWithReceipt("x2", null)));
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());
        when(mediaStore.delete(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();

        verify(mediaStore).delete("events/1/cover-a.jpg");
        verify(mediaStore).delete("events/1/receipts/x1-a.jpg");
        verify(mediaStore, times(2)).delete(anyString());
    }

    @Test
    void deleteEvent_shouldSucceed_whenWelyMediaCannotDiscard() {
        Event event = Event.builder().id("1").organizerId("organizer").image("events/1/cover-a.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.just(event));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());
        when(mediaStore.delete(anyString())).thenReturn(Mono.error(new EventException(EventErrorCode.MEDIA_UNAVAILABLE)));

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();
    }

    @Test
    void deleteEvent_shouldLeaveADemoCoverAlone() {
        Event event = Event.builder().id("1").organizerId("organizer").image("/demo/events/picnic.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.just(event));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();

        verify(mediaStore, never()).delete(anyString());
    }

    @Test
    void createEvent_shouldAlwaysSubscribeTheOrganizer() {
        Event event = Event.builder().title("Dinner").organizerId("org").build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event).block();

        assertThat(event.getParticipantIds()).containsExactly("org");
    }

    @Test
    void createEvent_shouldListTheOrganizerOnce_whenAlreadyGiven() {
        Event event = Event.builder().title("Dinner").organizerId("org").participantIds(List.of("org")).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event).block();

        assertThat(event.getParticipantIds()).containsExactly("org");
    }

    /** A private event: only its members see it, so no friendship lookup is involved. */
    private static Event quiz(String organizer, String... participants) {
        return Event.builder().id("1").title("Quiz").organizerId(organizer).visibility(Visibility.PRIVATE)
                .participantIds(new ArrayList<>(List.of(participants))).groupVersion(3L).build();
    }

    @Test
    void leave_shouldAnswerNotFound_whenTheEventIsMissing() {
        when(eventRepository.findById("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "lea", null, false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void leave_shouldAnswerNotFound_whenTheUserCannotSeeTheEvent() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org")));

        StepVerifier.create(eventService.leave("1", "lea", null, false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(eventRepository, never()).removeParticipant(any(), any());
    }

    @Test
    void leave_shouldReturnTheEventUnchanged_whenTheUserIsNotAMember() {
        Event event = by("org", Visibility.PUBLIC);
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.leave("1", "lea", null, false))
                .expectNext(LeaveOutcome.left(event))
                .verifyComplete();
        verify(eventRepository, never()).removeParticipant(any(), any());
    }

    @Test
    void leave_shouldRemoveAParticipant() {
        Event after = quiz("org", "org");
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));
        when(eventRepository.removeParticipant("1", "lea")).thenReturn(Mono.just(after));

        StepVerifier.create(eventService.leave("1", "lea", null, false))
                .expectNext(LeaveOutcome.left(after))
                .verifyComplete();
    }

    @Test
    void leave_shouldDecideAgain_whenTheParticipantWasMadeOrganizerMeanwhile() {
        Event handedOver = quiz("lea", "lea", "hugo");
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea", "hugo")), Mono.just(handedOver));
        when(eventRepository.removeParticipant("1", "lea")).thenReturn(Mono.just(handedOver));

        StepVerifier.create(eventService.leave("1", "lea", null, false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
    }

    @Test
    void leave_shouldAnswerNotFound_whenTheEventVanishesAsTheParticipantLeaves() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));
        when(eventRepository.removeParticipant("1", "lea")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "lea", null, false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void leave_shouldRequireANewOrganizer_whenOthersTakePart() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));

        StepVerifier.create(eventService.leave("1", "org", null, true))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
        StepVerifier.create(eventService.leave("1", "org", " ", true))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
        verify(eventRepository, never()).handOver(any(), any(), any());
        verify(eventRepository, never()).deleteIfAlone(any(), any(), any());
    }

    @Test
    void leave_shouldRejectASuccessorWhoDoesNotTakePart() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));

        StepVerifier.create(eventService.leave("1", "org", "hugo", false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_INVALID))
                .verify();
        verify(eventRepository, never()).handOver(any(), any(), any());
    }

    @Test
    void leave_shouldRejectTheOrganizerAsTheirOwnSuccessor() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));

        StepVerifier.create(eventService.leave("1", "org", "org", false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_INVALID))
                .verify();
    }

    @Test
    void leave_shouldHandTheEventOverToTheChosenParticipant() {
        Event handedOver = quiz("lea", "lea");
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));
        when(eventRepository.handOver("1", "org", "lea")).thenReturn(Mono.just(handedOver));

        StepVerifier.create(eventService.leave("1", "org", "lea", false))
                .expectNext(LeaveOutcome.left(handedOver))
                .verifyComplete();
        verify(eventRepository, never()).removeParticipant(any(), any());
    }

    @Test
    void leave_shouldAnswerInvalid_whenTheSuccessorLeftBeforeTheHandOver() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea", "hugo")),
                Mono.just(quiz("org", "org", "hugo")));
        when(eventRepository.handOver("1", "org", "lea")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", "lea", false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_INVALID))
                .verify();
    }

    @Test
    void leave_shouldAnswerRequired_whenTheHandOverRacedAnythingElse() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")),
                Mono.just(quiz("hugo", "hugo", "lea")));
        when(eventRepository.handOver("1", "org", "lea")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", "lea", false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
    }

    @Test
    void leave_shouldAnswerNotFound_whenTheEventWasDeletedBeforeTheHandOver() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")), Mono.empty());
        when(eventRepository.handOver("1", "org", "lea")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", "lea", false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void leave_shouldAskForConfirmation_whenTheOrganizerIsTheLastMember() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org")));

        StepVerifier.create(eventService.leave("1", "org", null, false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.LAST_MEMBER))
                .verify();
        verify(eventRepository, never()).deleteIfAlone(any(), any(), any());
    }

    @Test
    void leave_shouldDeleteTheEventWithItsExpensesAndTombstone_whenTheLastMemberConfirms() {
        Event alone = quiz("org", "org");
        alone.setImage("events/1/cover-a.jpg");
        when(eventRepository.findById("1")).thenReturn(Mono.just(alone));
        when(eventRepository.deleteIfAlone("1", "org", Set.of())).thenReturn(Mono.just(alone));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.just(expenseWithReceipt("x1", "events/1/receipts/x1-a.jpg")));
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());
        when(mediaStore.delete(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", null, true))
                .expectNext(LeaveOutcome.eventDeleted())
                .verifyComplete();

        verify(deletionOutbox).record(new EventDeletion("1", "Quiz", 4L, NOW));
        verify(expenseRepository).deleteByEvent("1");
        verify(mediaStore).delete("events/1/cover-a.jpg");
        verify(mediaStore).delete("events/1/receipts/x1-a.jpg");
    }

    @Test
    void leave_shouldCountAnOrganizerNotListedAsTheLastMember() {
        Event legacy = quiz("org");
        when(eventRepository.findById("1")).thenReturn(Mono.just(legacy));
        when(eventRepository.deleteIfAlone("1", "org", Set.of())).thenReturn(Mono.just(legacy));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", null, true))
                .expectNext(LeaveOutcome.eventDeleted())
                .verifyComplete();
    }

    @Test
    void leave_shouldAskForASuccessor_whenSomeoneJoinedBeforeTheDeletion() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org")), Mono.just(quiz("org", "org", "lea")));
        when(eventRepository.deleteIfAlone("1", "org", Set.of())).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", null, true))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
        verify(deletionOutbox, never()).record(any());
        verify(expenseRepository, never()).deleteByEvent(any());
    }

    @Test
    void leave_shouldAnswerNotFound_whenTheEventWasDeletedMeanwhile() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org")), Mono.empty());
        when(eventRepository.deleteIfAlone("1", "org", Set.of())).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", null, true))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void leave_shouldDeleteTheEvent_whenTheOtherParticipantsAreAllDeletedAccounts() {
        Event past = quiz("org", "org", "gone");
        when(eventRepository.findById("1")).thenReturn(Mono.just(past));
        when(markers.deletedAmong(List.of("org", "gone"))).thenReturn(Mono.just(Set.of("gone")));
        when(eventRepository.deleteIfAlone("1", "org", Set.of("gone"))).thenReturn(Mono.just(past));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.leave("1", "org", null, true))
                .expectNext(LeaveOutcome.eventDeleted())
                .verifyComplete();
        verify(eventRepository, never()).handOver(any(), any(), any());
    }

    @Test
    void leave_shouldAskForConfirmation_whenOnlyDeletedAccountsRemain() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "gone")));
        when(markers.deletedAmong(List.of("org", "gone"))).thenReturn(Mono.just(Set.of("gone")));

        StepVerifier.create(eventService.leave("1", "org", null, false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.LAST_MEMBER))
                .verify();
        verify(eventRepository, never()).deleteIfAlone(any(), any(), any());
    }

    @Test
    void leave_shouldRejectADeletedAccountAsSuccessor() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "gone", "lea")));
        when(markers.deletedAmong(List.of("org", "gone", "lea"))).thenReturn(Mono.just(Set.of("gone")));

        StepVerifier.create(eventService.leave("1", "org", "gone", false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_INVALID))
                .verify();
        verify(eventRepository, never()).handOver(any(), any(), any());
    }

    @Test
    void leave_shouldHandOver_whenALiveParticipantRemainsBesideDeletedOnes() {
        Event handedOver = quiz("lea", "gone", "lea");
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "gone", "lea")));
        when(markers.deletedAmong(List.of("org", "gone", "lea"))).thenReturn(Mono.just(Set.of("gone")));
        when(eventRepository.handOver("1", "org", "lea")).thenReturn(Mono.just(handedOver));

        StepVerifier.create(eventService.leave("1", "org", "lea", false))
                .expectNext(LeaveOutcome.left(handedOver))
                .verifyComplete();
    }

    @Test
    void leave_shouldAnswerTheStoreError_whenTheRegisterCannotBeRead() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));
        when(markers.deletedAmong(List.of("org", "lea")))
                .thenReturn(Mono.error(new EventException(EventErrorCode.STORE_UNAVAILABLE)));

        StepVerifier.create(eventService.leave("1", "org", "lea", false))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.STORE_UNAVAILABLE))
                .verify();
        verify(eventRepository, never()).handOver(any(), any(), any());
    }

    @Test
    void toggleSubscription_shouldSendTheOrganizerToLeave_ratherThanUnsubscribe() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));

        StepVerifier.create(eventService.toggleSubscription("1", "org"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
        verify(eventRepository, never()).removeParticipant(any(), any());
    }

    @Test
    void toggleSubscription_shouldRefuse_whenTheUserWasMadeOrganizerMeanwhile() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(quiz("org", "org", "lea")));
        when(eventRepository.removeParticipant("1", "lea")).thenReturn(Mono.just(quiz("lea", "lea")));

        StepVerifier.create(eventService.toggleSubscription("1", "lea"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
    }

    @Test
    void deleteEvent_shouldLeaveATombstoneOneVersionPastTheGroup() {
        Event event = quiz("organizer", "organizer");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.just(event));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();

        verify(deletionOutbox).record(new EventDeletion("1", "Quiz", 4L, NOW));
    }

    @Test
    void deleteEvent_shouldSucceed_whenTheTombstoneCannotBeWritten() {
        Event event = quiz("organizer", "organizer");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.just(event));
        when(deletionOutbox.record(any())).thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();

        verify(expenseRepository).deleteByEvent("1");
    }

    @Test
    void deleteEvent_shouldAnswerNotFound_whenNothingWasLeftToDelete() {
        Event event = quiz("organizer", "organizer");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteByOrganizer("1", "organizer")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(deletionOutbox, never()).record(any());
        verify(expenseRepository, never()).deleteByEvent(any());
    }

    private void storesUnderTheKeyGiven() {
        when(mediaStore.store(anyString(), any(), eq(MediaProfile.COVER)))
                .thenAnswer(call -> Mono.just(new StoredMedia(call.getArgument(0), 1600, 900)));
    }

    private static final EventDetails NEW_DETAILS =
            new EventDetails("Afterwork", "Ninkasi", new GeoPoint(45.73, 4.83), "First round on me", Visibility.PUBLIC);

    @Test
    void updateDetails_shouldRewriteTheOrganizersEvent_andRenameTheGroupWhenTheTitleChanges() {
        Event event = Event.builder().id("1").title("Old title").organizerId("organizer").visibility(Visibility.FRIENDS).build();
        Event updated = Event.builder().id("1").title("Afterwork").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.updateDetails("1", "organizer", NEW_DETAILS, true)).thenReturn(Mono.just(updated));

        StepVerifier.create(eventService.updateDetails("1", "organizer", NEW_DETAILS)).expectNext(updated).verifyComplete();
    }

    @Test
    void updateDetails_shouldLeaveTheGroupAlone_whenTheTitleIsTheSame() {
        Event event = Event.builder().id("1").title("Afterwork").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.updateDetails("1", "organizer", NEW_DETAILS, false)).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.updateDetails("1", "organizer", NEW_DETAILS)).expectNextCount(1).verifyComplete();
    }

    @Test
    void updateDetails_shouldKeepTheVisibility_whenNoneIsGiven_orCommunityIsAskedFor() {
        Event event = Event.builder().id("1").title("Afterwork").organizerId("organizer").visibility(Visibility.PRIVATE).build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.updateDetails(eq("1"), eq("organizer"), any(), eq(false))).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.updateDetails("1", "organizer", NEW_DETAILS.withVisibility(null))).expectNextCount(1).verifyComplete();
        StepVerifier.create(eventService.updateDetails("1", "organizer", NEW_DETAILS.withVisibility(Visibility.COMMUNITY))).expectNextCount(1).verifyComplete();

        verify(eventRepository, times(2)).updateDetails("1", "organizer", NEW_DETAILS.withVisibility(Visibility.PRIVATE), false);
    }

    @Test
    void updateDetails_shouldKeepACommunityEventInItsCommunity() {
        Event event = Event.builder().id("1").title("Afterwork").organizerId("organizer").communityId("c1")
                .visibility(Visibility.COMMUNITY).build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.updateDetails(eq("1"), eq("organizer"), any(), eq(false))).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.updateDetails("1", "organizer", NEW_DETAILS)).expectNextCount(1).verifyComplete();

        verify(eventRepository).updateDetails("1", "organizer", NEW_DETAILS.withVisibility(Visibility.COMMUNITY), false);
    }

    @Test
    void updateDetails_shouldRefuseAParticipant() {
        Event event = Event.builder().id("1").organizerId("organizer").participantIds(new ArrayList<>(List.of("alice"))).build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.updateDetails("1", "alice", NEW_DETAILS))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
        verify(eventRepository, never()).updateDetails(any(), any(), any(), anyBoolean());
    }

    @Test
    void updateDetails_shouldAnswerNotFound_toSomeoneWhoCannotSeeTheEvent() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("organizer", Visibility.PRIVATE)));

        StepVerifier.create(eventService.updateDetails("1", "mallory", NEW_DETAILS))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void updateDetails_shouldAnswerNotFound_whenTheEventIsGoneMeanwhile() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("organizer", Visibility.PRIVATE)));
        when(eventRepository.updateDetails(eq("1"), eq("organizer"), any(), anyBoolean())).thenReturn(Mono.empty());

        StepVerifier.create(eventService.updateDetails("1", "organizer", NEW_DETAILS))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void setCover_shouldStoreThenSetTheKey_andDiscardThePreviousCover() {
        Event event = Event.builder().id("1").organizerId("organizer").image("events/1/cover-old.jpg").build();
        Event updated = Event.builder().id("1").organizerId("organizer").image("events/1/cover-new.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        storesUnderTheKeyGiven();
        when(eventRepository.setImage(eq("1"), eq("organizer"), anyString())).thenReturn(Mono.just(updated));
        when(mediaStore.delete("events/1/cover-old.jpg")).thenReturn(Mono.empty());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

        StepVerifier.create(eventService.setCover("1", "organizer", new byte[]{1})).expectNext(updated).verifyComplete();

        verify(eventRepository).setImage(eq("1"), eq("organizer"), key.capture());
        assertThat(key.getValue()).matches("events/1/cover-[0-9a-f-]{36}\\.jpg");
        verify(mediaStore).delete("events/1/cover-old.jpg");
    }

    @Test
    void setCover_shouldNotDiscardADemoCover() {
        Event event = Event.builder().id("1").organizerId("organizer").image("/demo/events/picnic.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        storesUnderTheKeyGiven();
        when(eventRepository.setImage(eq("1"), eq("organizer"), anyString())).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.setCover("1", "organizer", new byte[]{1})).expectNextCount(1).verifyComplete();

        verify(mediaStore, never()).delete(anyString());
    }

    @Test
    void setCover_shouldRefuseAParticipant() {
        Event event = Event.builder().id("1").organizerId("organizer").participantIds(new ArrayList<>(List.of("alice"))).build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.setCover("1", "alice", new byte[]{1}))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
        verify(mediaStore, never()).store(any(), any(), any());
    }

    @Test
    void setCover_shouldAnswerNotFound_toSomeoneWhoCannotSeeTheEvent() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("organizer", Visibility.PRIVATE)));

        StepVerifier.create(eventService.setCover("1", "mallory", new byte[]{1}))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(mediaStore, never()).store(any(), any(), any());
    }

    @Test
    void setCover_shouldPassOnARefusedImage_withoutTouchingTheEvent() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("organizer", Visibility.PRIVATE)));
        when(mediaStore.store(anyString(), any(), eq(MediaProfile.COVER)))
                .thenReturn(Mono.error(new EventException(EventErrorCode.COVER_INVALID)));

        StepVerifier.create(eventService.setCover("1", "organizer", new byte[]{1}))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.COVER_INVALID))
                .verify();
        verify(eventRepository, never()).setImage(any(), any(), any());
    }

    @Test
    void setCover_shouldRemoveTheNewObject_whenTheEventIsGoneMeanwhile() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("organizer", Visibility.PRIVATE)));
        storesUnderTheKeyGiven();
        when(eventRepository.setImage(eq("1"), eq("organizer"), anyString())).thenReturn(Mono.empty());
        when(mediaStore.delete(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(eventService.setCover("1", "organizer", new byte[]{1}))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(mediaStore).delete(startsWith("events/1/cover-"));
    }

    @Test
    void setCover_shouldRemoveTheNewObject_whenTheUpdateFails() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("organizer", Visibility.PRIVATE)));
        storesUnderTheKeyGiven();
        when(eventRepository.setImage(eq("1"), eq("organizer"), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(mediaStore.delete(anyString())).thenReturn(Mono.empty());

        StepVerifier.create(eventService.setCover("1", "organizer", new byte[]{1})).verifyError(IllegalStateException.class);
        verify(mediaStore).delete(startsWith("events/1/cover-"));
    }

    @Test
    void removeCover_shouldClearTheField_thenDiscardTheObject() {
        Event event = Event.builder().id("1").organizerId("organizer").image("events/1/cover-a.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.setImage("1", "organizer", null)).thenReturn(Mono.just(by("organizer", Visibility.FRIENDS)));
        when(mediaStore.delete("events/1/cover-a.jpg")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.removeCover("1", "organizer")).verifyComplete();

        verify(mediaStore).delete("events/1/cover-a.jpg");
    }

    @Test
    void removeCover_shouldSucceed_whenWelyMediaCannotDiscard() {
        Event event = Event.builder().id("1").organizerId("organizer").image("events/1/cover-a.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.setImage("1", "organizer", null)).thenReturn(Mono.just(by("organizer", Visibility.FRIENDS)));
        when(mediaStore.delete("events/1/cover-a.jpg")).thenReturn(Mono.error(new EventException(EventErrorCode.MEDIA_UNAVAILABLE)));

        StepVerifier.create(eventService.removeCover("1", "organizer")).verifyComplete();
    }

    @Test
    void removeCover_shouldRefuseAParticipant() {
        Event event = Event.builder().id("1").organizerId("organizer").image("events/1/cover-a.jpg")
                .participantIds(new ArrayList<>(List.of("alice"))).build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.removeCover("1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
        verify(eventRepository, never()).setImage(any(), any(), any());
    }

    @Test
    void removeCover_shouldAnswerNotFound_whenMissing() {
        when(eventRepository.findById("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.removeCover("1", "organizer"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void removeCover_shouldAnswerNotFound_whenTheEventIsGoneMeanwhile() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("organizer", Visibility.PRIVATE)));
        when(eventRepository.setImage("1", "organizer", null)).thenReturn(Mono.empty());

        StepVerifier.create(eventService.removeCover("1", "organizer"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(mediaStore, never()).delete(anyString());
    }

    // --- Communities ------------------------------------------------------------------------------

    private static Event inCommunity(String organizer, String... participants) {
        Event event = withParticipants(organizer, Visibility.COMMUNITY, participants);
        event.setCommunityId("c1");
        return event;
    }

    @Test
    void createEvent_shouldPublishInACommunity_forAMember_withTheCommunitysVisibility() {
        Event event = Event.builder().title("Balade").organizerId("alice").communityId("c1").visibility(Visibility.PUBLIC).build();
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.just(CommunityRole.MEMBER));
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.createEvent(event, null)).expectNext(event).verifyComplete();

        assertThat(event.getVisibility()).isEqualTo(Visibility.COMMUNITY);
        assertThat(event.getParticipantIds()).containsExactly("alice");
        assertThat(event.getResponses()).isEmpty();
    }

    @Test
    void createEvent_shouldRefuseANonMember_withoutSaving() {
        Event event = Event.builder().title("Intrus").organizerId("alice").communityId("c1").build();
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.createEvent(event, null))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.COMMUNITY_MEMBERS_ONLY))
                .verify();
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_shouldNotLetTheClientChooseTheCommunityVisibility_withoutACommunity() {
        Event event = Event.builder().title("Pirate").organizerId("alice").visibility(Visibility.COMMUNITY).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event, null).block();

        assertThat(event.getVisibility()).isEqualTo(Visibility.FRIENDS);
    }

    @Test
    void getEventById_shouldShowACommunityEventToAMember() {
        Event event = inCommunity("bob", "bob");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.just(CommunityRole.MEMBER));

        StepVerifier.create(eventService.getEventById("1", "alice")).expectNext(event).verifyComplete();
    }

    @Test
    void getEventById_shouldHideACommunityEventFromANonMember() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(inCommunity("bob", "bob")));
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void getEventById_shouldHideACommunityVisibilityWithoutACommunity() {
        // Never written by the domain; read defensively all the same.
        when(eventRepository.findById("1")).thenReturn(Mono.just(withParticipants("bob", Visibility.COMMUNITY, "bob")));

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(communityDirectory, never()).roleOf(anyString(), anyString());
    }

    @Test
    void getCommunityEvents_shouldListTheUpcomingOnes_forAMember() {
        Event event = inCommunity("bob", "bob");
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.just(CommunityRole.MEMBER));
        when(eventRepository.findUpcomingInCommunity("c1", "alice", NOW)).thenReturn(Flux.just(event));

        StepVerifier.create(eventService.getCommunityEvents("c1", "alice")).expectNext(event).verifyComplete();
    }

    @Test
    void getCommunityEvents_shouldBeMembersOnly() {
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.getCommunityEvents("c1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.COMMUNITY_MEMBERS_ONLY))
                .verify();
        verify(eventRepository, never()).findUpcomingInCommunity(anyString(), any(), any());
    }

    @Test
    void deleteEvent_shouldLetACommunityAdminModerate() {
        Event event = inCommunity("bob", "bob");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(communityDirectory.roleOf("c1", "admin")).thenReturn(Mono.just(CommunityRole.ADMIN));
        when(eventRepository.deleteByOrganizer("1", "bob")).thenReturn(Mono.just(event));
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "admin")).verifyComplete();
        verify(eventRepository).deleteByOrganizer("1", "bob");
    }

    @Test
    void deleteEvent_shouldRefuseAPlainMemberOfTheCommunity() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(inCommunity("bob", "bob")));
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.just(CommunityRole.MEMBER));

        StepVerifier.create(eventService.deleteEvent("1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
        verify(eventRepository, never()).deleteByOrganizer(anyString(), anyString());
    }

    @Test
    void deleteEvent_shouldRefuseSomeoneWhoLeftTheCommunity() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(inCommunity("bob", "bob")));
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "alice"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
    }

    @Test
    void respond_shouldRecordAMembersAnswer() {
        Event event = inCommunity("bob", "bob");
        Event answered = inCommunity("bob", "bob", "alice");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.just(CommunityRole.MEMBER));
        when(eventRepository.respond("1", "alice", PollAnswer.YES)).thenReturn(Mono.just(answered));

        StepVerifier.create(eventService.respond("1", "alice", PollAnswer.YES)).expectNext(answered).verifyComplete();
    }

    @Test
    void respond_shouldAnswerNotFound_whenTheEventWentMeanwhile() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(inCommunity("bob", "bob", "alice")));
        when(eventRepository.respond("1", "alice", PollAnswer.NO)).thenReturn(Mono.empty());

        StepVerifier.create(eventService.respond("1", "alice", PollAnswer.NO))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void respond_shouldRefuseAnEventOutsideAnyCommunity() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(withParticipants("bob", Visibility.PUBLIC, "bob")));

        StepVerifier.create(eventService.respond("1", "alice", PollAnswer.YES))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NOT_A_COMMUNITY_EVENT))
                .verify();
        verify(eventRepository, never()).respond(anyString(), anyString(), any());
    }

    @Test
    void respond_shouldLetTheOrganizerSayYes_whichChangesNothing() {
        Event event = inCommunity("bob", "bob");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.respond("1", "bob", PollAnswer.YES)).expectNext(event).verifyComplete();
        verify(eventRepository, never()).respond(anyString(), anyString(), any());
    }

    @Test
    void respond_shouldSendTheOrganizerToAHandOver_forAnythingButYes() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(inCommunity("bob", "bob")));

        StepVerifier.create(eventService.respond("1", "bob", PollAnswer.MAYBE))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NEW_ORGANIZER_REQUIRED))
                .verify();
    }

    @Test
    void toggleSubscription_shouldAnswerYes_toACommunityEventNotTakenPartIn() {
        Event event = inCommunity("bob", "bob");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(communityDirectory.roleOf("c1", "alice")).thenReturn(Mono.just(CommunityRole.MEMBER));
        when(eventRepository.respond("1", "alice", PollAnswer.YES)).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.toggleSubscription("1", "alice")).expectNext(event).verifyComplete();
    }

    @Test
    void toggleSubscription_shouldAnswerNo_toACommunityEventTakenPartIn() {
        Event event = inCommunity("bob", "bob", "alice");
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.respond("1", "alice", PollAnswer.NO)).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.toggleSubscription("1", "alice")).expectNext(event).verifyComplete();
    }

    // --- Kinds, all-day events and hidden events --------------------------------------------------

    @Test
    void getEventById_shouldAskTheDatabaseForAnEventShownToTheViewer() {
        // doReturn: when(...) would run the delegating answer, and so findById, while stubbing.
        doReturn(Mono.empty()).when(eventRepository).findShownTo("1", "lea");

        StepVerifier.create(eventService.getEventById("1", "lea"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(eventRepository).findShownTo("1", "lea");
        verify(eventRepository, never()).findById(anyString());
    }

    @Test
    void createEvent_shouldRefuseABirthdayOrASurprise_whichHaveTheirOwnPaths() {
        for (EventKind kind : List.of(EventKind.BIRTHDAY, EventKind.SURPRISE)) {
            StepVerifier.create(eventService.createEvent(Event.builder().title("x").kind(kind).build(), null))
                    .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_KIND_INVALID))
                    .verify();
        }
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_shouldStoreAStandardEvent_withoutAnyBirthdayField() {
        Event event = Event.builder().title("Quiz").honoreeId("lea").honoreeName("Léa").organizerName("bob")
                .occurrenceKey("lea:2027").birthMonth(3).birthDay(12)
                .hiddenFrom(new ArrayList<>(List.of("lea"))).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event).block();

        assertThat(event.getKind()).isEqualTo(EventKind.STANDARD);
        assertThat(event.getHonoreeId()).isNull();
        assertThat(event.getHiddenFrom()).isEmpty();
        assertThat(event.getHonoreeName()).isNull();
        assertThat(event.getOrganizerName()).isNull();
        assertThat(event.getOccurrenceKey()).isNull();
        assertThat(event.birthMonthDay()).isNull();
    }

    @Test
    void createEvent_shouldMakeAnAllDayEventWithoutAnEndLastOneDay() {
        Instant day = Instant.parse("2026-11-03T00:00:00Z");
        Event event = Event.builder().title("Day off").allDay(true).startDate(day).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        eventService.createEvent(event).block();

        assertThat(event.getEndDate()).isEqualTo(Instant.parse("2026-11-04T00:00:00Z"));
    }

    @Test
    void createEvent_shouldKeepAnAllDayEventOverSeveralDays() {
        Event event = Event.builder().title("Holidays").allDay(true).startDate(Instant.parse("2026-11-03T00:00:00Z"))
                .endDate(Instant.parse("2026-11-06T00:00:00Z")).build();
        when(eventRepository.save(event)).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.createEvent(event)).expectNext(event).verifyComplete();
    }

    @Test
    void createEvent_shouldRefuseAnAllDayEventNotOnWholeUtcDays_orWithAPoll() {
        Instant midnight = Instant.parse("2026-11-03T00:00:00Z");
        List<Event> invalid = List.of(
                Event.builder().title("a").allDay(true).startDate(midnight.plusSeconds(3600)).build(),
                Event.builder().title("b").allDay(true).startDate(midnight).endDate(midnight.plusSeconds(3600)).build(),
                Event.builder().title("c").allDay(true).startDate(midnight).endDate(midnight).build(),
                Event.builder().title("d").allDay(true).build());
        for (Event event : invalid) {
            StepVerifier.create(eventService.createEvent(event))
                    .expectErrorMatches(e -> hasCode(e, EventErrorCode.ALL_DAY_INVALID))
                    .verify();
        }
        List<PollSlot> slots = List.of(new PollSlot(null, NOW.plusSeconds(86_400), NOW.plusSeconds(90_000)),
                new PollSlot(null, NOW.plusSeconds(172_800), NOW.plusSeconds(176_400)));
        StepVerifier.create(eventService.createEvent(Event.builder().title("e").allDay(true).startDate(midnight).build(), slots))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.ALL_DAY_INVALID))
                .verify();
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_shouldRefuseADateInThePast() {
        List<Event> past = List.of(
                Event.builder().title("a").startDate(NOW.minus(Duration.ofMinutes(6))).build(),
                Event.builder().title("b").startDate(Instant.parse("2026-10-10T18:00:00Z")).build(),
                Event.builder().title("c").allDay(true).startDate(Instant.parse("2026-10-10T00:00:00Z")).build());
        for (Event event : past) {
            StepVerifier.create(eventService.createEvent(event))
                    .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_IN_PAST))
                    .verify();
        }
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_shouldAcceptAStartJustBehindTheClock_orAnAllDayEventToday() {
        when(eventRepository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(eventService.createEvent(Event.builder().title("now")
                        .startDate(NOW.minus(Duration.ofMinutes(4))).build()))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(eventService.createEvent(Event.builder().title("today").allDay(true)
                        .startDate(Instant.parse("2026-10-11T00:00:00Z")).build()))
                .expectNextCount(1).verifyComplete();
    }

    @Test
    void updateDetails_shouldRequireALocation_onAnythingButASurprise() {
        Event event = Event.builder().id("1").title("Afterwork").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        StepVerifier.create(eventService.updateDetails("1", "organizer",
                        new EventDetails("Afterwork", " ", null, null, Visibility.PUBLIC)))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.LOCATION_REQUIRED))
                .verify();
        verify(eventRepository, never()).updateDetails(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    void updateDetails_shouldLetASurpriseHaveNoPlaceYet_andKeepItForTheHonoreesFriends() {
        Event surprise = Event.builder().id("1").title("Surprise").organizerId("organizer").kind(EventKind.SURPRISE)
                .honoreeId("lea").visibility(Visibility.FRIENDS).build();
        EventDetails details = new EventDetails("Surprise", null, null, "Chut", Visibility.PUBLIC);
        when(eventRepository.findById("1")).thenReturn(Mono.just(surprise));
        when(eventRepository.updateDetails("1", "organizer", details.withVisibility(Visibility.FRIENDS), false))
                .thenReturn(Mono.just(surprise));

        StepVerifier.create(eventService.updateDetails("1", "organizer", details)).expectNext(surprise).verifyComplete();
    }
}
