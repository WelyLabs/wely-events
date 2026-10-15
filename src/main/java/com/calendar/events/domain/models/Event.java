package com.calendar.events.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    /** How long an event without an end is taken to last, as the calendar displays it. */
    public static final Duration DEFAULT_DURATION = Duration.ofHours(1);

    private String id;
    private String title;
    private String organizerId;
    private Instant startDate;
    private Instant endDate;
    private String location;
    /** Null when the location was typed rather than picked on the map. */
    private GeoPoint coordinates;
    private String image;
    private String description;
    @Builder.Default
    private List<String> participantIds = new ArrayList<>();
    /** Null for events stored before visibility existed; read through {@link #effectiveVisibility()}. */
    private Visibility visibility;
    /** Null unless the organizer proposed several dates instead of one. */
    private Poll poll;
    /** Read from storage only, for the deletion's version; null on events stored before group chats. */
    private Long groupVersion;

    /** When the event stops keeping its people busy. */
    public Instant effectiveEnd() {
        return endDate != null ? endDate : startDate.plus(DEFAULT_DURATION);
    }

    /** Whether the date is still being voted on: then startDate/endDate are only the earliest proposal. */
    public boolean isPolling() {
        return poll != null && poll.isOpen();
    }

    public Visibility effectiveVisibility() {
        return visibility != null ? visibility : Visibility.DEFAULT;
    }

    /** Whether the user organizes the event or takes part in it. */
    public boolean involves(String userId) {
        return userId.equals(organizerId) || participantIds.contains(userId);
    }

    /** Whether someone besides the organizer takes part. */
    public boolean hasOtherParticipants() {
        return participantIds.stream().anyMatch(id -> !id.equals(organizerId));
    }

    /**
     * Whether the user may see this event, given who the user's friends are. Those taking part
     * always can; otherwise it depends on the visibility.
     */
    public boolean isVisibleTo(String userId, Set<String> friendIdsOfUser) {
        if (involves(userId)) {
            return true;
        }
        return switch (effectiveVisibility()) {
            case PUBLIC -> true;
            case FRIENDS -> friendIdsOfUser.contains(organizerId);
            case PRIVATE -> false;
        };
    }
}
