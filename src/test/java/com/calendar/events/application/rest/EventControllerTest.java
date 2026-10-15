package com.calendar.events.application.rest;

import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.models.EventDetails;
import com.calendar.events.application.dtos.EventUpdateRequest;
import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.LeaveRequest;
import com.calendar.events.application.dtos.LeaveResponse;
import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.dtos.SlotRequest;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.application.dtos.ResponseRequest;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.LeaveOutcome;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.domain.services.EventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {

    @Mock
    private EventService eventService;

    @Mock
    private EventRestMapper mapper;

    @Mock
    private MediaStore mediaStore;

    @Mock
    private Jwt jwt;

    private EventController controller;

    @BeforeEach
    void setUp() {
        controller = new EventController(eventService, new PictureService(mediaStore), mapper);
    }

    @Test
    void getMySubscribedEvents_shouldReturnSubscribedEvents() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.getSubscribedEvents("user1")).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

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
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

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
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

        Mono<EventResponse> result = controller.getEventById("1", jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void getParticipants_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Participants participants = new Participants(2, List.of(), List.of("user2"));
        ParticipantsResponse response = new ParticipantsResponse(2, List.of(), List.of("user2"));

        when(eventService.getParticipants("1", "user1")).thenReturn(Mono.just(participants));
        when(mapper.toResponse(participants)).thenReturn(response);

        StepVerifier.create(controller.getParticipants("1", jwt))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void getParticipants_shouldPassOnNotFound() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getParticipants("1", "user1"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)));

        StepVerifier.create(controller.getParticipants("1", jwt))
                .expectError(EventException.class)
                .verify();
    }

    @Test
    void createEvent_shouldReturnCreatedEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        EventRequest request = EventRequest.builder().title("Title").build();
        Event event = Event.builder().title("Title").participantIds(new ArrayList<>()).build();
        EventResponse response = EventResponse.builder().id("1").title("Title").build();

        when(mapper.toDomain(request)).thenReturn(event);
        when(eventService.createEvent(any(Event.class), any())).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

        Mono<EventResponse> result = controller.createEvent(request, jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void createEvent_shouldHandTheCandidateSlotsToTheService() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        List<SlotRequest> slots = List.of(
                new SlotRequest(Instant.parse("2026-10-16T18:00:00Z"), Instant.parse("2026-10-16T21:00:00Z")),
                new SlotRequest(Instant.parse("2026-10-17T18:00:00Z"), Instant.parse("2026-10-17T21:00:00Z")));
        EventRequest request = EventRequest.builder().title("Title").candidateSlots(slots).build();
        Event event = Event.builder().title("Title").participantIds(new ArrayList<>()).build();
        List<PollSlot> candidates = List.of(new PollSlot(null, slots.get(0).start(), slots.get(0).end()),
                new PollSlot(null, slots.get(1).start(), slots.get(1).end()));
        EventResponse response = EventResponse.builder().id("1").build();

        when(mapper.toDomain(request)).thenReturn(event);
        when(mapper.toCandidateSlots(slots)).thenReturn(candidates);
        when(eventService.createEvent(event, candidates)).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.createEvent(request, jwt))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void updateEvent_shouldRewriteTheDetailsAsTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        EventUpdateRequest request = new EventUpdateRequest("Afterwork", "Ninkasi", null, null, Visibility.PUBLIC);
        EventDetails details = new EventDetails("Afterwork", "Ninkasi", null, null, Visibility.PUBLIC);
        Event event = Event.builder().id("1").title("Afterwork").build();
        EventResponse response = EventResponse.builder().id("1").title("Afterwork").build();
        when(mapper.toDetails(request)).thenReturn(details);
        when(eventService.updateDetails("1", "user1", details)).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.updateEvent("1", request, jwt)).expectNext(response).verifyComplete();
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
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

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
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

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

    /** The real mapping, to see which image each event ends up with. */
    private EventController withRealMapping() {
        return new EventController(eventService, new PictureService(mediaStore), Mappers.getMapper(EventRestMapper.class));
    }

    private static Event coveredBy(String id, String image) {
        return Event.builder().id(id).image(image).build();
    }

    @Test
    void getMyFeedEvents_shouldSignEveryStoredCoverInOneCall() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getFeedEvents("user1")).thenReturn(Flux.just(coveredBy("1", "events/1/cover-a.jpg"),
                coveredBy("2", "/demo/events/picnic.jpg"), coveredBy("3", null), coveredBy("4", "events/4/cover-b.jpg")));
        when(mediaStore.urls(List.of("events/1/cover-a.jpg", "events/4/cover-b.jpg"))).thenReturn(Mono.just(Map.of(
                "events/1/cover-a.jpg", "https://media/a", "events/4/cover-b.jpg", "https://media/b")));

        StepVerifier.create(withRealMapping().getMyFeedEvents(jwt).map(response -> String.valueOf(response.getImage())))
                .expectNext("https://media/a", "/demo/events/picnic.jpg", "null", "https://media/b")
                .verifyComplete();
        verify(mediaStore, times(1)).urls(any());
    }

    @Test
    void getMyFeedEvents_shouldDropStoredCovers_ratherThanFail_whenWelyMediaIsDown() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getFeedEvents("user1")).thenReturn(Flux.just(coveredBy("1", "events/1/cover-a.jpg"),
                coveredBy("2", "/demo/events/picnic.jpg")));
        when(mediaStore.urls(any())).thenReturn(Mono.error(new EventException(EventErrorCode.MEDIA_UNAVAILABLE)));

        StepVerifier.create(withRealMapping().getMyFeedEvents(jwt).map(response -> String.valueOf(response.getImage())))
                .expectNext("null", "/demo/events/picnic.jpg")
                .verifyComplete();
    }

    @Test
    void getEventById_shouldSignItsStoredCover() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getEventById("1", "user1")).thenReturn(Mono.just(coveredBy("1", "events/1/cover-a.jpg")));
        when(mediaStore.urls(List.of("events/1/cover-a.jpg"))).thenReturn(Mono.just(Map.of("events/1/cover-a.jpg", "https://media/a")));

        StepVerifier.create(withRealMapping().getEventById("1", jwt))
                .expectNextMatches(response -> "https://media/a".equals(response.getImage()))
                .verifyComplete();
    }

    @Test
    void setCover_shouldStoreTheFileAsTheCaller_andAnswerTheSignedEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event covered = coveredBy("1", "events/1/cover-a.jpg");
        SignedUrls urls = new SignedUrls(Map.of("events/1/cover-a.jpg", "https://media/a"));
        EventResponse response = EventResponse.builder().id("1").image("https://media/a").build();
        when(eventService.setCover(eq("1"), eq("user1"), any())).thenReturn(Mono.just(covered));
        when(mediaStore.urls(List.of("events/1/cover-a.jpg"))).thenReturn(Mono.just(urls.byKey()));
        when(mapper.toResponse(covered, urls)).thenReturn(response);

        StepVerifier.create(controller.setCover("1", UploadsTest.form(UploadsTest.file(4096)), jwt))
                .expectNext(response)
                .verifyComplete();
        verify(eventService).setCover(eq("1"), eq("user1"), argThat(bytes -> bytes.length == 4096));
    }

    @Test
    void setCover_shouldRefuseAFileOver10Megabytes_beforeTheService() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");

        StepVerifier.create(controller.setCover("1", UploadsTest.form(UploadsTest.file(Uploads.MAX_BYTES + 1)), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, EventErrorCode.MEDIA_TOO_LARGE))
                .verify();
        verify(eventService, never()).setCover(any(), any(), any());
    }

    @Test
    void setCover_shouldRefuseAFormWithoutFile_asAnInvalidCover() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");

        StepVerifier.create(controller.setCover("1", UploadsTest.form(null), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, EventErrorCode.COVER_INVALID))
                .verify();
    }

    @Test
    void setCover_shouldPassOnARefusal() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.setCover(eq("1"), eq("user1"), any()))
                .thenReturn(Mono.error(new EventException(EventErrorCode.EVENT_ACCESS_DENIED)));

        StepVerifier.create(controller.setCover("1", UploadsTest.form(UploadsTest.file(10)), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, EventErrorCode.EVENT_ACCESS_DENIED))
                .verify();
    }

    @Test
    void removeCover_shouldRemoveAsTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.removeCover("1", "user1")).thenReturn(Mono.empty());

        StepVerifier.create(controller.removeCover("1", jwt)).verifyComplete();

        verify(eventService).removeCover("1", "user1");
    }

    @Test
    void createEvent_shouldMakeTheCallerTheOrganizer() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        EventRequest request = EventRequest.builder().title("Title").build();
        Event event = Event.builder().title("Title").participantIds(new ArrayList<>()).build();
        when(mapper.toDomain(request)).thenReturn(event);
        when(eventService.createEvent(any(Event.class), any())).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(EventResponse.builder().build());

        controller.createEvent(request, jwt).block();

        // Subscribing the organizer is the domain's rule, not the controller's.
        verify(eventService).createEvent(argThat(created -> "user1".equals(created.getOrganizerId())
                && created.getParticipantIds().isEmpty()), any());
    }

    @Test
    void leave_shouldLeaveAsTheCaller_andAnswerTheEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        LeaveOutcome outcome = LeaveOutcome.left(event);
        LeaveResponse response = new LeaveResponse(false, EventResponse.builder().id("1").build());
        when(eventService.leave("1", "user1", "lea", false)).thenReturn(Mono.just(outcome));
        when(mapper.toResponse(outcome, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.leave("1", new LeaveRequest("lea", false), jwt))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void leave_shouldReadNoBodyAsNeitherSuccessorNorConfirmation() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.leave("1", "user1", null, false))
                .thenReturn(Mono.error(new EventException(EventErrorCode.LAST_MEMBER)));

        StepVerifier.create(controller.leave("1", null, jwt))
                .expectErrorMatches(error -> error instanceof EventException e && e.getErrorCode() == EventErrorCode.LAST_MEMBER)
                .verify();
    }

    @Test
    void leave_shouldAnswerDeletedWithoutEvent_whenTheLastMemberLeft() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.leave("1", "user1", null, true)).thenReturn(Mono.just(LeaveOutcome.eventDeleted()));

        StepVerifier.create(withRealMapping().leave("1", new LeaveRequest(null, true), jwt))
                .expectNext(new LeaveResponse(true, null))
                .verifyComplete();
        verify(mediaStore, never()).urls(any());
    }

    @Test
    void leave_shouldSignTheCoverOfTheEventLeft() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.leave("1", "user1", "lea", false))
                .thenReturn(Mono.just(LeaveOutcome.left(coveredBy("1", "events/1/cover-a.jpg"))));
        when(mediaStore.urls(List.of("events/1/cover-a.jpg"))).thenReturn(Mono.just(Map.of("events/1/cover-a.jpg", "https://media/a")));

        StepVerifier.create(withRealMapping().leave("1", new LeaveRequest("lea", false), jwt))
                .expectNextMatches(response -> !response.deleted() && "https://media/a".equals(response.event().getImage()))
                .verifyComplete();
    }

    @Test
    void getCommunityEvents_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").communityId("c1").build();
        EventResponse response = EventResponse.builder().id("1").communityId("c1").build();
        when(eventService.getCommunityEvents("c1", "user1")).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.getCommunityEvents("c1", jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void respondTo_shouldRecordTheCallersAnswer() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").communityId("c1").build();
        EventResponse response = EventResponse.builder().id("1").build();
        when(eventService.respond("1", "user1", PollAnswer.MAYBE)).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.respondTo("1", new ResponseRequest(PollAnswer.MAYBE), jwt))
                .expectNext(response)
                .verifyComplete();
    }
}
