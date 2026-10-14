package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.models.StoredMedia;
import com.calendar.events.domain.models.Visibility;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private EventService eventService;

    private static final Instant NOW = Instant.parse("2026-10-11T16:00:00Z");
    private static final GeoBox LYON = new GeoBox(45.70, 4.77, 45.81, 4.92);

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, socialGraph, expenseRepository, new PictureService(mediaStore),
                Clock.fixed(NOW, ZoneOffset.UTC));
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
    void getParticipants_shouldNameNoFriend_whenTheSocialServiceIsDown() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(withParticipants("carol", Visibility.PUBLIC, "alice")));
        when(socialGraph.friendIdsOf("carol"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));

        StepVerifier.create(eventService.getParticipants("1", "carol"))
                .expectNext(new Participants(1, List.of("alice"), List.of()))
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
        when(eventRepository.deleteById("1")).thenReturn(Mono.empty());
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer"))
                .verifyComplete();
        verify(eventRepository, times(1)).deleteById("1");
    }

    @Test
    void deleteEvent_shouldTakeItsExpensesWithIt() {
        Event event = Event.builder().id("1").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteById("1")).thenReturn(Mono.empty());
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();

        verify(expenseRepository).deleteByEvent("1");
    }

    @Test
    void deleteEvent_shouldKeepTheExpenses_whenTheEventCannotBeDeleted() {
        Event event = Event.builder().id("1").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteById("1")).thenReturn(Mono.error(new IllegalStateException("mongo down")));

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
        verify(eventRepository, never()).deleteById(any());
    }

    @Test
    void deleteEvent_shouldFailWhenEventDoesNotExist() {
        when(eventRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("missing", "organizer"))
                .expectErrorMatches(e -> e instanceof EventException
                        && ((EventException) e).getErrorCode() == EventErrorCode.EVENT_NOT_FOUND)
                .verify();
        verify(eventRepository, never()).deleteById(any());
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
    void getFeedEvents_shouldPassTheUsersFriends() {
        Event event = Event.builder().id("2").build();
        when(socialGraph.friendIdsOf("user123")).thenReturn(Mono.just(Set.of("bob")));
        when(eventRepository.findFeed("user123", Set.of("bob"))).thenReturn(Flux.just(event));

        StepVerifier.create(eventService.getFeedEvents("user123"))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getFeedEvents_shouldFallBackToNoFriends_whenTheSocialServiceIsDown() {
        when(socialGraph.friendIdsOf("user123"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));
        when(eventRepository.findFeed("user123", Set.of())).thenReturn(Flux.empty());

        StepVerifier.create(eventService.getFeedEvents("user123")).verifyComplete();

        verify(eventRepository).findFeed("user123", Set.of());
    }

    @Test
    void getFeedEvents_shouldNotHideOtherFailures() {
        when(socialGraph.friendIdsOf("user123")).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(eventService.getFeedEvents("user123"))
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
    void getMapEvents_shouldFallBackToNoFriends_whenTheSocialServiceIsDown() {
        when(socialGraph.friendIdsOf("user123"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));
        when(eventRepository.findOnMap("user123", Set.of(), LYON, NOW, EventService.MAP_LIMIT)).thenReturn(Flux.empty());

        StepVerifier.create(eventService.getMapEvents("user123", LYON)).verifyComplete();

        verify(eventRepository).findOnMap("user123", Set.of(), LYON, NOW, EventService.MAP_LIMIT);
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
                SplitMode.EQUAL, List.of(new Share("organizer", 0)), NOW, receiptKey, 0L);
    }

    @Test
    void deleteEvent_shouldDiscardTheCoverAndEveryReceipt() {
        Event event = Event.builder().id("1").organizerId("organizer").image("events/1/cover-a.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteById("1")).thenReturn(Mono.empty());
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
        when(eventRepository.deleteById("1")).thenReturn(Mono.empty());
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());
        when(mediaStore.delete(anyString())).thenReturn(Mono.error(new EventException(EventErrorCode.MEDIA_UNAVAILABLE)));

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();
    }

    @Test
    void deleteEvent_shouldLeaveADemoCoverAlone() {
        Event event = Event.builder().id("1").organizerId("organizer").image("/demo/events/picnic.jpg").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteById("1")).thenReturn(Mono.empty());
        when(expenseRepository.findByEvent("1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer")).verifyComplete();

        verify(mediaStore, never()).delete(anyString());
    }

    private void storesUnderTheKeyGiven() {
        when(mediaStore.store(anyString(), any(), eq(MediaProfile.COVER)))
                .thenAnswer(call -> Mono.just(new StoredMedia(call.getArgument(0), 1600, 900)));
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
}
