package com.calendar.events.domain.models;

import java.time.Instant;
import java.util.List;

/**
 * What two friends have shared on Wely, for the anniversary screen.
 *
 * @param years       whole years of friendship, in the user's time zone
 * @param days        days of friendship, in the user's time zone
 * @param first       their first shared outing; null when they never had one
 * @param last        their latest shared outing; null when they never had one
 * @param highlights  up to six outings, those with a cover first, the most recent first
 */
public record FriendshipMemories(String friendId, String name, Closeness closeness, Instant since, int years,
                                 long days, int sharedCount, Event first, Event last, List<Event> highlights) {

    public FriendshipMemories {
        highlights = List.copyOf(highlights);
    }
}
