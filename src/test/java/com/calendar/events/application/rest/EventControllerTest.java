package com.calendar.events.application.rest;

import com.calendar.events.domain.services.FriendshipService;
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
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.application.dtos.ResponseRequest;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.LeaveOutcome;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.models.FeedRank;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.SurpriseService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThat;
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
    private FriendshipService friendshipService;

    @Mock
    private EventService eventService;

    @Mock
    private EventRestMapper mapper;

    @Mock
    private MediaStore mediaStore;

    @Mock
    private Jwt jwt;

    @Mock
    private SurpriseService surpriseService;

    private EventController controller;

    @BeforeEach
    void setUp() {
        lenient().when(friendshipService.viewerFriends(anyString())).thenReturn(Mono.just(ViewerFriends.NONE));
        controller = new EventController(eventService, surpriseService, new PictureService(mediaStore), friendshipService,
                mapper);
    }

    @Test
    void getMySubscribedEvents_shouldReturnSubscribedEvents() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.getSubscribedEvents("user1")).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        Flux<EventResponse> result = controller.getMySubscribedEvents(null, null, jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMySubscribedEvents_shouldNarrowToTheRange_whenBothBoundsAreGiven() {
        Instant from = Instant.parse("2026-10-21T00:00:00Z");
        Instant to = Instant.parse("2026-10-22T00:00:00Z");
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.getSubscribedEvents("user1", from, to)).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.getMySubscribedEvents(from, to, jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMySubscribedEvents_shouldAskTheServiceToJudgeAHalfGivenRange() {
        Instant from = Instant.parse("2026-10-21T00:00:00Z");
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getSubscribedEvents("user1", from, null))
                .thenReturn(Flux.error(new BusinessException(BusinessErrorCode.TIME_RANGE_INVALID)));

        StepVerifier.create(controller.getMySubscribedEvents(from, null, jwt))
                .expectError(BusinessException.class)
                .verify(Duration.ofSeconds(10));

        verify(eventService, never()).getSubscribedEvents("user1");
    }

    @Test
    void getMyFeedEvents_shouldReturnFeedEvents() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("2").build();
        EventResponse response = EventResponse.builder().id("2").build();

        when(eventService.getFeedEvents("user1", ViewerFriends.NONE, FeedRank.DEFAULT)).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        Flux<EventResponse> result = controller.getMyFeedEvents(null, jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMyFeedEvents_shouldRankByAffinity_withTheCallersFriends() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        ViewerFriends friends = new ViewerFriends(Map.of("bob", com.calendar.events.domain.models.Closeness.CLOSE));
        when(friendshipService.viewerFriends("user1")).thenReturn(Mono.just(friends));
        Event event = Event.builder().id("2").build();
        EventResponse response = EventResponse.builder().id("2").build();
        when(eventService.getFeedEvents("user1", friends, FeedRank.AFFINITY)).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, friends)).thenReturn(response);

        StepVerifier.create(controller.getMyFeedEvents("Affinity", jwt)).expectNext(response).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMyFeedEvents_shouldRefuseAnUnknownRank() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");

        StepVerifier.create(controller.getMyFeedEvents("popularity", jwt))
                .expectErrorMatches(error -> error instanceof BusinessException e
                        && e.getErrorCode() == BusinessErrorCode.FEED_RANK_INVALID)
                .verify(Duration.ofSeconds(10));
        verify(eventService, never()).getFeedEvents(any(), any(), any());
    }

    @Test
    void getEventById_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.getEventById("1", "user1")).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        Mono<EventResponse> result = controller.getEventById("1", jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getParticipants_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Participants participants = new Participants(2, List.of(), List.of("user2"), List.of());
        ParticipantsResponse response = new ParticipantsResponse(2, List.of(), List.of("user2"), List.of());

        when(eventService.getParticipants("1", "user1")).thenReturn(Mono.just(participants));
        when(mapper.toResponse(participants)).thenReturn(response);

        StepVerifier.create(controller.getParticipants("1", jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getParticipants_shouldPassOnNotFound() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getParticipants("1", "user1"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND)));

        StepVerifier.create(controller.getParticipants("1", jwt))
                .expectError(BusinessException.class)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void createEvent_shouldReturnCreatedEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        EventRequest request = EventRequest.builder().title("Title").build();
        Event event = Event.builder().title("Title").participantIds(new ArrayList<>()).build();
        EventResponse response = EventResponse.builder().id("1").title("Title").build();

        when(mapper.toDomain(request)).thenReturn(event);
        when(eventService.createEvent(any(Event.class), any())).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        Mono<EventResponse> result = controller.createEvent(request, jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
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
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.createEvent(request, jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
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
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.updateEvent("1", request, jwt)).expectNext(response).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void deleteEvent_shouldReturnVoid() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.deleteEvent("1", "user1")).thenReturn(Mono.empty());

        Mono<Void> result = controller.deleteEvent("1", jwt);

        StepVerifier.create(result)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void toggleSubscription_shouldReturnUpdatedEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();

        when(eventService.toggleSubscription("1", "user1")).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        Mono<EventResponse> result = controller.toggleSubscription("1", jwt);

        StepVerifier.create(result)
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMapEvents_shouldShowTheCallersEventsInTheArea() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").build();
        EventResponse response = EventResponse.builder().id("1").build();
        when(eventService.getMapEvents("user1", new GeoBox(45.70, 4.77, 45.81, 4.92))).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.getMapEvents(45.70, 4.77, 45.81, 4.92, jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMapEvents_shouldRejectAnAreaUpsideDown() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");

        StepVerifier.create(controller.getMapEvents(45.81, 4.77, 45.70, 4.92, jwt))
                .expectErrorMatches(error -> error instanceof BusinessException e
                        && e.getErrorCode() == BusinessErrorCode.INVALID_MAP_AREA)
                .verify(Duration.ofSeconds(10));
    }

    /** The real mapping, to see which image each event ends up with. */
    private EventController withRealMapping() {
        return new EventController(eventService, surpriseService, new PictureService(mediaStore), friendshipService,
                Mappers.getMapper(EventRestMapper.class));
    }

    private static Event coveredBy(String id, String image) {
        return Event.builder().id(id).image(image).build();
    }

    @Test
    void getMyFeedEvents_shouldSignEveryStoredCoverInOneCall() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getFeedEvents("user1", ViewerFriends.NONE, FeedRank.DEFAULT)).thenReturn(Flux.just(coveredBy("1", "events/1/cover-a.jpg"),
                coveredBy("2", "/demo/events/picnic.jpg"), coveredBy("3", null), coveredBy("4", "events/4/cover-b.jpg")));
        when(mediaStore.urls(List.of("events/1/cover-a.jpg", "events/4/cover-b.jpg"))).thenReturn(Mono.just(Map.of(
                "events/1/cover-a.jpg", "https://media/a", "events/4/cover-b.jpg", "https://media/b")));

        StepVerifier.create(withRealMapping().getMyFeedEvents(null, jwt).map(response -> String.valueOf(response.getImage())))
                .expectNext("https://media/a", "/demo/events/picnic.jpg", "null", "https://media/b")
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(mediaStore, times(1)).urls(any());
    }

    @Test
    void getMyFeedEvents_shouldDropStoredCovers_ratherThanFail_whenWelyMediaIsDown() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getFeedEvents("user1", ViewerFriends.NONE, FeedRank.DEFAULT)).thenReturn(Flux.just(coveredBy("1", "events/1/cover-a.jpg"),
                coveredBy("2", "/demo/events/picnic.jpg")));
        when(mediaStore.urls(any())).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.MEDIA_UNAVAILABLE)));

        StepVerifier.create(withRealMapping().getMyFeedEvents(null, jwt).map(response -> String.valueOf(response.getImage())))
                .expectNext("null", "/demo/events/picnic.jpg")
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getEventById_shouldSignItsStoredCover() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.getEventById("1", "user1")).thenReturn(Mono.just(coveredBy("1", "events/1/cover-a.jpg")));
        when(mediaStore.urls(List.of("events/1/cover-a.jpg"))).thenReturn(Mono.just(Map.of("events/1/cover-a.jpg", "https://media/a")));

        StepVerifier.create(withRealMapping().getEventById("1", jwt))
                .expectNextMatches(response -> "https://media/a".equals(response.getImage()))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void setCover_shouldStoreTheFileAsTheCaller_andAnswerTheSignedEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event covered = coveredBy("1", "events/1/cover-a.jpg");
        SignedUrls urls = new SignedUrls(Map.of("events/1/cover-a.jpg", "https://media/a"));
        EventResponse response = EventResponse.builder().id("1").image("https://media/a").build();
        when(eventService.setCover(eq("1"), eq("user1"), any())).thenReturn(Mono.just(covered));
        when(mediaStore.urls(List.of("events/1/cover-a.jpg"))).thenReturn(Mono.just(urls.byKey()));
        when(mapper.toResponse(covered, urls, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.setCover("1", UploadsTest.form(UploadsTest.file(4096)), jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(eventService).setCover(eq("1"), eq("user1"), argThat(bytes -> bytes.length == 4096));
    }

    @Test
    void setCover_shouldRefuseAFileOver10Megabytes_beforeTheService() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");

        StepVerifier.create(controller.setCover("1", UploadsTest.form(UploadsTest.file(Uploads.MAX_BYTES + 1)), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, BusinessErrorCode.MEDIA_TOO_LARGE))
                .verify(Duration.ofSeconds(10));
        verify(eventService, never()).setCover(any(), any(), any());
    }

    @Test
    void setCover_shouldRefuseAFormWithoutFile_asAnInvalidCover() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");

        StepVerifier.create(controller.setCover("1", UploadsTest.form(null), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, BusinessErrorCode.COVER_INVALID))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void setCover_shouldPassOnARefusal() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.setCover(eq("1"), eq("user1"), any()))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.EVENT_ACCESS_DENIED)));

        StepVerifier.create(controller.setCover("1", UploadsTest.form(UploadsTest.file(10)), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, BusinessErrorCode.EVENT_ACCESS_DENIED))
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void removeCover_shouldRemoveAsTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.removeCover("1", "user1")).thenReturn(Mono.empty());

        StepVerifier.create(controller.removeCover("1", jwt)).expectComplete().verify(Duration.ofSeconds(10));

        verify(eventService).removeCover("1", "user1");
    }

    @Test
    void createEvent_shouldMakeTheCallerTheOrganizer() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        EventRequest request = EventRequest.builder().title("Title").build();
        Event event = Event.builder().title("Title").participantIds(new ArrayList<>()).build();
        when(mapper.toDomain(request)).thenReturn(event);
        when(eventService.createEvent(any(Event.class), any())).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(EventResponse.builder().build());

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
        when(mapper.toResponse(outcome, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.leave("1", new LeaveRequest("lea", false), jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void leave_shouldReadNoBodyAsNeitherSuccessorNorConfirmation() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.leave("1", "user1", null, false))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.LAST_MEMBER)));

        StepVerifier.create(controller.leave("1", null, jwt))
                .expectErrorMatches(error -> error instanceof BusinessException e && e.getErrorCode() == BusinessErrorCode.LAST_MEMBER)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void leave_shouldAnswerDeletedWithoutEvent_whenTheLastMemberLeft() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        when(eventService.leave("1", "user1", null, true)).thenReturn(Mono.just(LeaveOutcome.eventDeleted()));

        StepVerifier.create(withRealMapping().leave("1", new LeaveRequest(null, true), jwt))
                .expectNext(new LeaveResponse(true, null))
                .expectComplete().verify(Duration.ofSeconds(10));
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
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getCommunityEvents_shouldAskOnBehalfOfTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").communityId("c1").build();
        EventResponse response = EventResponse.builder().id("1").communityId("c1").build();
        when(eventService.getCommunityEvents("c1", "user1")).thenReturn(Flux.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.getCommunityEvents("c1", jwt)).expectNext(response).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void respondTo_shouldRecordTheCallersAnswer() {
        when(jwt.getClaimAsString("businessId")).thenReturn("user1");
        Event event = Event.builder().id("1").communityId("c1").build();
        EventResponse response = EventResponse.builder().id("1").build();
        when(eventService.respond("1", "user1", PollAnswer.MAYBE)).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.respondTo("1", new ResponseRequest(PollAnswer.MAYBE), jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void createEvent_shouldHandASurpriseToTheSurpriseService_namingTheOrganizerFromTheToken() {
        when(jwt.getClaimAsString("businessId")).thenReturn("bob");
        when(jwt.getClaimAsString(EventController.USERNAME_CLAIM)).thenReturn("bob_l");
        EventRequest request = EventRequest.builder().title("Surprise").kind(EventKind.SURPRISE).honoreeId("lea").build();
        Event event = Event.builder().title("Surprise").kind(EventKind.SURPRISE).honoreeId("lea")
                .participantIds(new ArrayList<>()).build();
        EventResponse response = EventResponse.builder().id("s1").build();
        when(mapper.toDomain(request)).thenReturn(event);
        when(surpriseService.createSurprise(eq(event), any(), eq("bob_l"))).thenReturn(Mono.just(event));
        when(mapper.toResponse(event, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.createEvent(request, jwt)).expectNext(response).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(event.getOrganizerId()).isEqualTo("bob");
        verify(eventService, never()).createEvent(any(Event.class), any());
    }
}
