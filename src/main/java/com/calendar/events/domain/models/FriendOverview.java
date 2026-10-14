package com.calendar.events.domain.models;

import java.util.List;

/**
 * What the caller shares with a friend, and what the friend has coming that the caller could join.
 *
 * @param outingsTogether  past events both took part in or organized
 * @param upcomingTogether next events both are on, soonest first
 * @param memoriesTogether last past events both were on, most recent first
 * @param friendUpcoming   the friend's next events the caller may see but is not on, soonest first
 */
public record FriendOverview(
        int outingsTogether,
        List<Event> upcomingTogether,
        List<Event> memoriesTogether,
        List<Event> friendUpcoming
) {
    public FriendOverview {
        upcomingTogether = List.copyOf(upcomingTogether);
        memoriesTogether = List.copyOf(memoriesTogether);
        friendUpcoming = List.copyOf(friendUpcoming);
    }
}
