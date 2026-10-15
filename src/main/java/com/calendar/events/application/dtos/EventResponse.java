package com.calendar.events.application.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.calendar.events.domain.models.Visibility;

import java.time.Instant;

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
}
