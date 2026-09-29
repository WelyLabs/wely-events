package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.ports.EventRepository;
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

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock
    private EventRepository eventRepository;

    private EventService eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventService(eventRepository);
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

    @Test
    void getEventById_shouldReturnMonoOfEvent() {
        Event event = Event.builder().id("1").title("Test Event").build();
        when(eventRepository.findById("1")).thenReturn(Mono.just(event));

        Mono<Event> result = eventService.getEventById("1");

        StepVerifier.create(result)
                .expectNext(event)
                .verifyComplete();
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
    void toggleSubscription_shouldAddUserIdIfNotPresent() {
        List<String> participants = new ArrayList<>();
        Event event = Event.builder().id("1").participantIds(participants).build();

        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.save(any(Event.class))).thenReturn(Mono.just(event));

        Mono<Event> result = eventService.toggleSubscription("1", "user123");

        StepVerifier.create(result)
                .expectNextMatches(evt -> evt.getParticipantIds().contains("user123"))
                .verifyComplete();

        verify(eventRepository).save(argThat(evt -> evt.getParticipantIds().contains("user123")));
    }

    @Test
    void toggleSubscription_shouldRemoveUserIdIfPresent() {
        List<String> participants = new ArrayList<>();
        participants.add("user123");
        Event event = Event.builder().id("1").participantIds(participants).build();

        when(eventRepository.findById("1")).thenReturn(Mono.just(event));
        when(eventRepository.save(any(Event.class))).thenReturn(Mono.just(event));

        Mono<Event> result = eventService.toggleSubscription("1", "user123");

        StepVerifier.create(result)
                .expectNextMatches(evt -> !evt.getParticipantIds().contains("user123"))
                .verifyComplete();

        verify(eventRepository).save(argThat(evt -> !evt.getParticipantIds().contains("user123")));
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
    void getFeedEvents_shouldReturnFeedEvents() {
        Event event = Event.builder().id("2").build();
        when(eventRepository.findByParticipantIdNot("user123")).thenReturn(Flux.just(event));

        Flux<Event> result = eventService.getFeedEvents("user123");

        StepVerifier.create(result)
                .expectNext(event)
                .verifyComplete();
    }
}
