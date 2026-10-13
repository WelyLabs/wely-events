package com.calendar.events.application.dtos;

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
        List<EventBalanceResponse> balances
) {
    public record CompanionResponse(String userId, int outingsTogether) {}

    public record PlaceResponse(String label, CoordinatesDto coordinates, int visits) {}

    public record EventBalanceResponse(String eventId, String title, long netCents) {}
}
