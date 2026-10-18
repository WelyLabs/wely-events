package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.CalendarEntryResponse;
import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventCover;
import com.calendar.events.domain.models.EventDetails;
import com.calendar.events.domain.models.EventResponses;
import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.LeaveOutcome;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollSlotTally;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.application.dtos.EventRequest;
import com.calendar.events.application.dtos.EventUpdateRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.EventResponsesResponse;
import com.calendar.events.application.dtos.FriendGoingResponse;
import com.calendar.events.application.dtos.LeaveResponse;
import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.dtos.PollResponse;
import com.calendar.events.application.dtos.PollSlotResponse;
import com.calendar.events.application.dtos.SlotRequest;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", imports = {EventCover.class, EventResponses.class})
public interface EventRestMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "participantIds", ignore = true)
    @Mapping(target = "organizerId", ignore = true)
    @Mapping(target = "image", ignore = true)
    // Opened by the domain from the candidate slots, which are mapped on their own.
    @Mapping(target = "poll", ignore = true)
    // Read from storage only.
    @Mapping(target = "groupVersion", ignore = true)
    @Mapping(target = "responses", ignore = true)
    // Set by the domain: who a surprise is hidden from, and what it is for.
    @Mapping(target = "hiddenFrom", ignore = true)
    @Mapping(target = "birthMonth", ignore = true)
    @Mapping(target = "birthDay", ignore = true)
    @Mapping(target = "honoreeName", ignore = true)
    @Mapping(target = "organizerName", ignore = true)
    @Mapping(target = "occurrenceKey", ignore = true)
    Event toDomain(EventRequest request);

    EventDetails toDetails(EventUpdateRequest request);

    List<PollSlot> toCandidateSlots(List<SlotRequest> slots);

    // Ids are given by Poll.open.
    @Mapping(target = "id", ignore = true)
    PollSlot toCandidateSlot(SlotRequest slot);

    // A stored cover is shown through its signed URL; urls comes from one signing round per response,
    // and friends from one read of the caller's friendships.
    @Mapping(target = "visibility", expression = "java(domain.effectiveVisibility())")
    @Mapping(target = "image", expression = "java(EventCover.of(domain).shownWith(urls))")
    @Mapping(target = "responses", expression = "java(toResponse(EventResponses.of(domain)))")
    @Mapping(target = "kind", expression = "java(domain.effectiveKind())")
    @Mapping(target = "friendsGoing", expression = "java(toFriendGoingResponses(friends.friendsGoingTo(domain).friends()))")
    @Mapping(target = "friendsGoingCount", expression = "java(friends.friendsGoingTo(domain).count())")
    @Mapping(target = "affinity", expression = "java(friends.friendsGoingTo(domain).affinity())")
    EventResponse toResponse(Event domain, @Context SignedUrls urls, @Context ViewerFriends friends);

    List<FriendGoingResponse> toFriendGoingResponses(List<Friend> friends);

    FriendGoingResponse toResponse(Friend friend);

    EventResponsesResponse toResponse(EventResponses responses);

    // The event, if any, goes through toResponse(Event, urls, friends) with the same signed URLs.
    LeaveResponse toResponse(LeaveOutcome outcome, @Context SignedUrls urls, @Context ViewerFriends friends);

    CalendarEntryResponse toResponse(CalendarEntry entry);

    ParticipantsResponse toResponse(Participants participants);

    @Mapping(target = "slots", expression = "java(toSlotResponses(poll.tally()))")
    PollResponse toResponse(Poll poll);

    List<PollSlotResponse> toSlotResponses(List<PollSlotTally> tallies);

    @Mapping(target = "id", source = "slot.id")
    @Mapping(target = "start", source = "slot.start")
    @Mapping(target = "end", source = "slot.end")
    PollSlotResponse toResponse(PollSlotTally tally);
}
