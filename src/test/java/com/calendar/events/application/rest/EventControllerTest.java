package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.domain.services.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {

    @Mock
    private EventService eventService;

    @Mock
    private EventRestMapper mapper;

    @Mock
    private Jwt jwt;

    private EventController controller;

    @BeforeEach
    void setUp() {
        controller = new EventController(eventService, mapper);
    }

    @Test
    void getMySubscribedEvents_shouldReturnSubscribedEvents() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.getSubscribedEvents("user1")).thenReturn(Flux.just(event));
        when(mapper.toResponse(event)).thenReturn(response);

        Flux<EventResponse> result = controller.getMySubscribedEvents(jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void getMyFeedEvents_shouldReturnFeedEvents() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("2").build();
        EventResponse response = EventResponse.builder().id("2").build();

        when(eventService.getFeedEvents("user1")).thenReturn(Flux.just(event));
        when(mapper.toResponse(event)).thenReturn(response);

        Flux<EventResponse> result = controller.getMyFeedEvents(jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void getEventById_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.getEventById("1", "user1")).thenReturn(Mono.just(event));
        when(mapper.toResponse(event)).thenReturn(response);

        Mono<EventResponse> result = controller.getEventById("1", jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void createEvent_shouldReturnCreatedEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        EventRequest request = EventRequest.builder().title("Title").subscribeByDefault(true).build();
        Event event = Event.builder().title("Title").participantIds(new ArrayList<>()).build();
        EventResponse response = EventResponse.builder().id("1").title("Title").build();

        when(mapper.toDomain(request)).thenReturn(event);
        when(eventService.createEvent(any(Event.class))).thenReturn(Mono.just(event));
        when(mapper.toResponse(event)).thenReturn(response);

        Mono<EventResponse> result = controller.createEvent(request, jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void deleteEvent_shouldReturnVoid() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.deleteEvent("1", "user1")).thenReturn(Mono.empty());

        Mono<Void> result = controller.deleteEvent("1", jwt);

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    void toggleSubscription_shouldReturnUpdatedEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.toggleSubscription("1", "user1")).thenReturn(Mono.just(event));
        when(mapper.toResponse(event)).thenReturn(response);

        Mono<EventResponse> result = controller.toggleSubscription("1", jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void getMapEvents_shouldShowTheCallersEventsInTheArea() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();
        when(eventService.getMapEvents("user1", new GeoBox(45.70, 4.77, 45.81, 4.92))).thenReturn(Flux.just(event));
        when(mapper.toResponse(event)).thenReturn(response);

        StepVerifier.create(controller.getMapEvents(45.70, 4.77, 45.81, 4.92, jwt))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void getMapEvents_shouldRejectAnAreaUpsideDown() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");

        StepVerifier.create(controller.getMapEvents(45.81, 4.77, 45.70, 4.92, jwt))
                .expectErrorMatches(error -> error instanceof EventException e
                        && e.getErrorCode() == EventErrorCode.INVALID_MAP_AREA)
                .verify();
    }
}
