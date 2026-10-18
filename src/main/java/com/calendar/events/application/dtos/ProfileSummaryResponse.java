package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Closeness;

import java.time.Instant;
import java.util.List;

/** A member's profile summary; see {@code ProfileSummary}. Names are resolved by the client. */
public record ProfileSummaryResponse(
        int outingsThisYear,
        int outingsThisMonth,
        int organizedThisYear,
        int placesThisYear,
        List<EventResponse> upcoming,
        List<EventResponse> memories,
        int pastCount,
        List<CompanionResponse> circle,
        List<PlaceResponse> places,
        List<EventBalanceResponse> balances,
        List<NewFriendResponse> newFriendsThisYear,
        List<UpgradeResponse> upgradesThisYear,
        List<CompanionResponse> topFriendsThisYear
) {
    public record CompanionResponse(String userId, int outingsTogether) {}

    public record PlaceResponse(String label, CoordinatesDto coordinates, int visits) {}

    public record EventBalanceResponse(String eventId, String title, long netCents) {}

    public record NewFriendResponse(String userId, Instant since) {}

    public record UpgradeResponse(String userId, Closeness closeness, Instant at) {}
}
