package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Closeness;

import java.time.Instant;
import java.util.List;

/** What two friends shared; {@code first} and {@code last} are null when they never went out together. */
public record FriendshipMemoriesResponse(
        String friendId,
        String name,
        Closeness closeness,
        Instant since,
        int years,
        long days,
        int sharedCount,
        MemoryResponse first,
        MemoryResponse last,
        List<MemoryResponse> highlights
) {
    /** One shared outing; {@code imageUrl} is its signed cover, a web app path, or null. */
    public record MemoryResponse(String eventId, String title, Instant startDate, String imageUrl) {}
}
