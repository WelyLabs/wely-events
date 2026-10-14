package com.calendar.events.application.dtos;

import java.util.List;

/** A friend's page; see {@code FriendOverview}. Names are resolved by the client. */
public record FriendOverviewResponse(
        int outingsTogether,
        List<EventResponse> upcomingTogether,
        List<EventResponse> memoriesTogether,
        List<EventResponse> friendUpcoming
) {
}
