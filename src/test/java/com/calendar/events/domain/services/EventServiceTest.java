package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendDirectory;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private FriendDirectory friendDirectory;

    private EventService eventService;

    private static final Instant NOW = Instant.parse("2026-10-11T16:00:00Z");
    private static final GeoBox LYON = new GeoBox(45.70, 4.77, 45.81, 4.92);

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository, friendDirectory, Clock.fixed(NOW, ZoneOffset.UTC));
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
        verifyNoInteractions(friendDirectory);
    }

    @Test
    void getEventById_shouldReturnAFriendsEvent_whenTheOrganizerIsAFriend() {
        Event event = by("bob", Visibility.FRIENDS);
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(friendDirectory.findFriendIds("alice")).thenReturn(Mono.just(Set.of("bob")));

        StepVerifier.create(eventService.getEventById("1", "alice"))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getEventById_shouldAnswerNotFound_whenTheOrganizerIsNotAFriend() {
        when(eventRepository.findById("1")).thenReturn(Mono.just(by("carol", Visibility.FRIENDS)));
        when(friendDirectory.findFriendIds("alice")).thenReturn(Mono.just(Set.of("bob")));

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
        verifyNoInteractions(friendDirectory);
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
    void createEvent_shouldReturnSavedEvent() {
        Event event = Event.builder().title("New Event").build();
        Event savedEvent = Event.builder().id("1").title("New Event").build();
        when(eventRepository.save(event)).thenReturn(Mono.just(savedEvent));

        Mono<Event> result = eventService.createEvent(event);

        StepVerifier.create(result)
                .expectNext(savedEvent)
                .verifyComplete();
    }

    @Test
    void deleteEvent_shouldDeleteWhenRequesterIsOrganizer() {
        Event event = Event.builder().id("1").organizerId("organizer").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.deleteById("1")).thenReturn(Mono.empty());

        StepVerifier.create(eventService.deleteEvent("1", "organizer"))
                .verifyComplete();
        verify(eventRepository, times(1)).deleteById("1");
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
        when(friendDirectory.findFriendIds("user123")).thenReturn(Mono.just(Set.of()));

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
        when(friendDirectory.findFriendIds("user123")).thenReturn(Mono.just(Set.of("bob")));
        when(eventRepository.findFeed("user123", Set.of("bob"))).thenReturn(Flux.just(event));

        StepVerifier.create(eventService.getFeedEvents("user123"))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getFeedEvents_shouldFallBackToNoFriends_whenTheSocialServiceIsDown() {
        when(friendDirectory.findFriendIds("user123"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));
        when(eventRepository.findFeed("user123", Set.of())).thenReturn(Flux.empty());

        StepVerifier.create(eventService.getFeedEvents("user123")).verifyComplete();

        verify(eventRepository).findFeed("user123", Set.of());
    }

    @Test
    void getFeedEvents_shouldNotHideOtherFailures() {
        when(friendDirectory.findFriendIds("user123")).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(eventService.getFeedEvents("user123"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void getMapEvents_shouldAskForWhatIsVisibleInTheAreaFromNowOn() {
        Event event = Event.builder().id("2").build();
        when(friendDirectory.findFriendIds("user123")).thenReturn(Mono.just(Set.of("bob")));
        when(eventRepository.findOnMap("user123", Set.of("bob"), LYON, NOW, EventService.MAP_LIMIT)).thenReturn(Flux.just(event));

        StepVerifier.create(eventService.getMapEvents("user123", LYON))
                .expectNext(event)
                .verifyComplete();
    }

    @Test
    void getMapEvents_shouldFallBackToNoFriends_whenTheSocialServiceIsDown() {
        when(friendDirectory.findFriendIds("user123"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));
        when(eventRepository.findOnMap("user123", Set.of(), LYON, NOW, EventService.MAP_LIMIT)).thenReturn(Flux.empty());

        StepVerifier.create(eventService.getMapEvents("user123", LYON)).verifyComplete();

        verify(eventRepository).findOnMap("user123", Set.of(), LYON, NOW, EventService.MAP_LIMIT);
    }

    @Test
    void getMapEvents_shouldNotHideOtherFailures() {
        when(friendDirectory.findFriendIds("user123")).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(eventService.getMapEvents("user123", LYON))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
