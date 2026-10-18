package com.calendar.events.application.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.Visibility;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventResponse {
    private String id;
    private String title;
    private String organizerId;
    private Instant startDate;
    private Instant endDate;
    private String location;
    private CoordinatesDto coordinates;
    /** A signed URL for an uploaded cover, a path the web app serves for demo covers, or null. */
    private String image;
    private String description;
    private Visibility visibility;
    /** Null unless the event's date was put to a poll. */
    private PollResponse poll;
    /** The community the event was published in, or null. */
    private String communityId;
    /** Who answered what, for an event published in a community only; null otherwise. */
    private EventResponsesResponse responses;
    /** Whole UTC days, from startDate to the exclusive endDate. */
    private boolean allDay;
    private EventKind kind;
    /** Whose birthday a SURPRISE celebrates; null on a standard event. */
    private String honoreeId;
    /** The caller's friends among the organizer and participants, closest first, at most 5; null on a birthday. */
    private List<FriendGoingResponse> friendsGoing;
    /** How many of the caller's friends go, uncapped; null on a birthday. */
    private Integer friendsGoingCount;
    /** The closest closeness among them: 0 none, 1 FRIEND, 2 CLOSE, 3 INTIMATE; null on a birthday. */
    private Integer affinity;
}
