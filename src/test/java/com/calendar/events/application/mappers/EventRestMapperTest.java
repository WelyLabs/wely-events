package com.calendar.events.application.mappers;

import com.calendar.events.domain.models.EventDetails;
import com.calendar.events.application.dtos.EventUpdateRequest;
import com.calendar.events.application.dtos.PollResponse;
import com.calendar.events.application.dtos.PollSlotResponse;
import com.calendar.events.application.dtos.SlotRequest;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.LeaveResponse;
import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.dtos.CalendarEntryResponse;
import com.calendar.events.application.dtos.CoordinatesDto;
import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.application.dtos.FriendGoingResponse;
import com.calendar.events.application.dtos.EventResponsesResponse;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.LeaveOutcome;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.models.Visibility;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EventRestMapperTest {

    private final EventRestMapper mapper = Mappers.getMapper(EventRestMapper.class);

    @Test
    void toDetails_shouldCarryEveryField_andTheCoordinates() {
        EventUpdateRequest request = new EventUpdateRequest("T", "L", new CoordinatesDto(45.7, 4.8), "D", Visibility.PRIVATE);

        assertEquals(new EventDetails("T", "L", new GeoPoint(45.7, 4.8), "D", Visibility.PRIVATE), mapper.toDetails(request));
        assertNull(mapper.toDetails(new EventUpdateRequest("T", "L", null, null, null)).coordinates());
    }

    @Test
    void toDomain_shouldMapRequestToDomain() {
        EventRequest request = new EventRequest();
        request.setTitle("Test Event");
        request.setStartDate(Instant.now());
        request.setEndDate(Instant.now().plusSeconds(3600));
        request.setLocation("Paris");
        request.setDescription("Desc");

        Event domain = mapper.toDomain(request);

        assertNotNull(domain);
        assertEquals(request.getTitle(), domain.getTitle());
        assertEquals(request.getStartDate(), domain.getStartDate());
        assertEquals(request.getEndDate(), domain.getEndDate());
        assertEquals(request.getLocation(), domain.getLocation());
        assertEquals(request.getDescription(), domain.getDescription());
        assertNull(domain.getId()); // Ignored in mapping
        assertNull(domain.getOrganizerId()); // Ignored in mapping
    }

    @Test
    void toResponse_shouldMapDomainToResponse() {
        Event domain = Event.builder()
                .id("1")
                .title("Test Event")
                .organizerId("org1")
                .startDate(Instant.now())
                .endDate(Instant.now().plusSeconds(3600))
                .location("Paris")
                .description("Desc")
                .participantIds(Collections.singletonList("user1"))
                .build();

        EventResponse response = mapper.toResponse(domain, SignedUrls.NONE, ViewerFriends.NONE);

        assertNotNull(response);
        assertEquals(domain.getId(), response.getId());
        assertEquals(domain.getTitle(), response.getTitle());
        assertEquals(domain.getOrganizerId(), response.getOrganizerId());
        assertEquals(domain.getStartDate(), response.getStartDate());
        assertEquals(domain.getEndDate(), response.getEndDate());
        assertEquals(domain.getLocation(), response.getLocation());
        assertEquals(domain.getDescription(), response.getDescription());
    }

    @Test
    void toDomain_shouldCarryTheChosenVisibility() {
        EventRequest request = EventRequest.builder().title("t").visibility(Visibility.PUBLIC).build();

        assertEquals(Visibility.PUBLIC, mapper.toDomain(request).getVisibility());
    }

    @Test
    void toResponse_shouldGiveACommunityEventsAnswers() {
        Event domain = Event.builder().id("1").organizerId("org").communityId("c1").visibility(Visibility.COMMUNITY)
                .participantIds(new java.util.ArrayList<>(List.of("org", "lea")))
                .responses(new java.util.LinkedHashMap<>(java.util.Map.of("bob", PollAnswer.MAYBE))).build();

        EventResponse response = mapper.toResponse(domain, SignedUrls.NONE, ViewerFriends.NONE);

        assertEquals("c1", response.getCommunityId());
        assertEquals(new EventResponsesResponse(List.of("org", "lea"), List.of("bob"), List.of()), response.getResponses());
    }

    @Test
    void toResponse_shouldGiveNoAnswers_forAnEventOutsideAnyCommunity() {
        EventResponse response = mapper.toResponse(Event.builder().id("1").build(), SignedUrls.NONE, ViewerFriends.NONE);

        assertNull(response.getResponses());
        assertNull(response.getCommunityId());
    }

    @Test
    void toDomain_shouldKeepTheCommunity_andNoAnswers() {
        Event event = mapper.toDomain(EventRequest.builder().title("t").location("l").communityId("c1").build());

        assertEquals("c1", event.getCommunityId());
    }

    @Test
    void toResponse_shouldReportAMissingVisibilityAsFriends() {
        EventResponse response = mapper.toResponse(Event.builder().id("1").build(), SignedUrls.NONE, ViewerFriends.NONE);

        assertEquals(Visibility.FRIENDS, response.getVisibility());
    }

    @Test
    void toResponse_shouldMapACalendarEntryAsIs() {
        Instant start = Instant.parse("2026-10-17T18:00:00Z");
        CalendarEntry entry = new CalendarEntry(start, start.plusSeconds(3600), null, "Dinner", null, null, false);

        assertEquals(new CalendarEntryResponse(start, start.plusSeconds(3600), null, "Dinner", null, null, false),
                mapper.toResponse(entry));
    }

    @Test
    void toDomain_shouldCarryThePointPickedOnTheMap() {
        EventRequest request = EventRequest.builder().title("t").coordinates(new CoordinatesDto(45.764, 4.8357)).build();

        assertEquals(new GeoPoint(45.764, 4.8357), mapper.toDomain(request).getCoordinates());
    }

    @Test
    void toResponse_shouldGiveBackTheCoordinates() {
        Event domain = Event.builder().coordinates(new GeoPoint(45.764, 4.8357)).build();

        assertEquals(new CoordinatesDto(45.764, 4.8357), mapper.toResponse(domain, SignedUrls.NONE, ViewerFriends.NONE).getCoordinates());
    }

    @Test
    void toDomain_shouldLeaveATypedLocationWithoutCoordinates() {
        assertNull(mapper.toDomain(EventRequest.builder().title("t").location("Lyon").build()).getCoordinates());
        assertNull(mapper.toResponse(Event.builder().location("Lyon").build(), SignedUrls.NONE, ViewerFriends.NONE).getCoordinates());
    }

    @Test
    void toResponse_shouldMapParticipantsAsIs() {
        ParticipantsResponse response = mapper.toResponse(new Participants(3, List.of("a", "b", "c"), List.of("b"), List.of("d"), List.of("e")));

        assertEquals(new ParticipantsResponse(3, List.of("a", "b", "c"), List.of("b"), List.of("d"), List.of("e")), response);
    }

    @Test
    void toDomain_shouldNeverTakeACover_fromTheRequest() {
        assertNull(mapper.toDomain(EventRequest.builder().title("t").build()).getImage());
    }

    @Test
    void toResponse_shouldShowAStoredCoverThroughItsSignedUrl() {
        Event domain = Event.builder().image("events/1/cover-a.jpg").build();

        assertEquals("https://media/a",
                mapper.toResponse(domain, new SignedUrls(Map.of("events/1/cover-a.jpg", "https://media/a")), ViewerFriends.NONE).getImage());
        assertNull(mapper.toResponse(domain, SignedUrls.NONE, ViewerFriends.NONE).getImage());
    }

    @Test
    void toResponse_shouldPassADemoCoverThrough() {
        Event domain = Event.builder().image("/demo/events/picnic.jpg").build();

        assertEquals("/demo/events/picnic.jpg", mapper.toResponse(domain, SignedUrls.NONE, ViewerFriends.NONE).getImage());
    }

    @Test
    void toCandidateSlots_shouldLeaveTheIdsToThePoll() {
        Instant start = Instant.parse("2026-10-16T17:00:00Z");
        Instant end = Instant.parse("2026-10-16T19:00:00Z");

        List<PollSlot> slots = mapper.toCandidateSlots(List.of(new SlotRequest(start, end)));

        assertEquals(List.of(new PollSlot(null, start, end)), slots);
        assertNull(mapper.toCandidateSlots(null));
    }

    @Test
    void toResponse_shouldIncludeThePollTally() {
        PollSlot slot = new PollSlot("thu", Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"));
        Poll poll = new Poll(PollStatus.OPEN, List.of(slot),
                Map.of("lea", Map.of("thu", PollAnswer.YES), "hugo", Map.of("thu", PollAnswer.MAYBE)), null);
        Event domain = Event.builder().id("1").startDate(slot.start()).poll(poll).build();

        EventResponse response = mapper.toResponse(domain, SignedUrls.NONE, ViewerFriends.NONE);

        assertEquals(PollStatus.OPEN, response.getPoll().status());
        assertNull(response.getPoll().fixedSlotId());
        assertEquals(List.of(new PollSlotResponse("thu", slot.start(), slot.end(), List.of("lea"), List.of("hugo"), List.of())),
                response.getPoll().slots());
    }

    @Test
    void toResponse_shouldLeaveThePollNull_forAnEventWithAFixedDate() {
        Event domain = Event.builder().id("1").startDate(Instant.now()).build();

        assertNull(mapper.toResponse(domain, SignedUrls.NONE, ViewerFriends.NONE).getPoll());
    }

    @Test
    void toResponse_shouldMapAClosedPoll() {
        Poll poll = new Poll(PollStatus.CLOSED, List.of(), Map.of(), "thu");

        PollResponse response = mapper.toResponse(poll);

        assertEquals(PollStatus.CLOSED, response.status());
        assertEquals("thu", response.fixedSlotId());
        assertEquals(List.of(), response.slots());
    }

    @Test
    void toResponse_shouldMapALeaveWithTheEventSigned() {
        Event domain = Event.builder().id("1").image("events/1/cover-a.jpg").build();

        LeaveResponse response = mapper.toResponse(LeaveOutcome.left(domain),
                new SignedUrls(Map.of("events/1/cover-a.jpg", "https://media/a")), ViewerFriends.NONE);

        assertFalse(response.deleted());
        assertEquals("1", response.event().getId());
        assertEquals("https://media/a", response.event().getImage());
    }

    @Test
    void toResponse_shouldMapADeletionWithoutEvent() {
        assertEquals(new LeaveResponse(true, null), mapper.toResponse(LeaveOutcome.eventDeleted(), SignedUrls.NONE, ViewerFriends.NONE));
    }

    @Test
    void toDomain_shouldNeverTakeAGroupVersion_fromTheRequest() {
        assertNull(mapper.toDomain(EventRequest.builder().title("t").build()).getGroupVersion());
    }

    @Test
    void toDomain_shouldCarryAllDayTheKindAndTheHonoree_butNothingTheDomainSets() {
        EventRequest request = EventRequest.builder().title("Surprise").allDay(true)
                .kind(com.calendar.events.domain.models.EventKind.SURPRISE).honoreeId("lea").build();

        Event event = mapper.toDomain(request);

        assertTrue(event.isAllDay());
        assertEquals(com.calendar.events.domain.models.EventKind.SURPRISE, event.getKind());
        assertEquals("lea", event.getHonoreeId());
        assertTrue(event.getHiddenFrom().isEmpty());
        assertNull(event.getOccurrenceKey());
    }

    @Test
    void toResponse_shouldGiveAllDayTheKindAndTheHonoree_aMissingKindAsStandard() {
        Event surprise = Event.builder().id("s1").allDay(true).kind(com.calendar.events.domain.models.EventKind.SURPRISE)
                .honoreeId("lea").hiddenFrom(new java.util.ArrayList<>(java.util.List.of("lea"))).build();

        EventResponse response = mapper.toResponse(surprise, SignedUrls.NONE, ViewerFriends.NONE);

        assertTrue(response.isAllDay());
        assertEquals(com.calendar.events.domain.models.EventKind.SURPRISE, response.getKind());
        assertEquals("lea", response.getHonoreeId());
        assertEquals(com.calendar.events.domain.models.EventKind.STANDARD,
                mapper.toResponse(Event.builder().id("e1").build(), SignedUrls.NONE, ViewerFriends.NONE).getKind());
    }

    @Test
    void toResponse_shouldNameTheCallersFriendsGoing_closestFirst() {
        Event domain = Event.builder().id("1").organizerId("ana")
                .participantIds(new java.util.ArrayList<>(List.of("ana", "bob", "zoe", "max"))).build();
        ViewerFriends friends = new ViewerFriends(Map.of("ana", Closeness.FRIEND,
                "zoe", Closeness.INTIMATE));

        EventResponse response = mapper.toResponse(domain, SignedUrls.NONE, friends);

        assertEquals(List.of(
                new FriendGoingResponse("zoe", Closeness.INTIMATE),
                new FriendGoingResponse("ana", Closeness.FRIEND)),
                response.getFriendsGoing());
        assertEquals(2, response.getFriendsGoingCount());
        assertEquals(3, response.getAffinity());
    }

    @Test
    void toResponse_shouldShowNoFriendsGoing_asZero_andHideThemOnABirthday() {
        EventResponse none = mapper.toResponse(Event.builder().id("1").organizerId("ana").build(), SignedUrls.NONE,
                ViewerFriends.NONE);
        EventResponse birthday = mapper.toResponse(Event.builder().id("b").organizerId("ana").kind(EventKind.BIRTHDAY).build(),
                SignedUrls.NONE, new ViewerFriends(Map.of("ana", Closeness.CLOSE)));

        assertEquals(List.of(), none.getFriendsGoing());
        assertEquals(0, none.getFriendsGoingCount());
        assertEquals(0, none.getAffinity());
        assertNull(birthday.getFriendsGoing());
        assertNull(birthday.getFriendsGoingCount());
        assertNull(birthday.getAffinity());
    }
}
