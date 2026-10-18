package com.calendar.events.domain.models;

import java.util.List;

/**
 * The viewer's friends among an event's organizer and participants: the closest few, how many in all,
 * and the closest closeness among them. Every field is null on an event that does not show them.
 *
 * @param friends  the closest first, the organizer then join order within a level, at most {@link #SHOWN}
 * @param count    every friend on it, uncapped
 * @param affinity the closest closeness among them: 0 none, 1 FRIEND, 2 CLOSE, 3 INTIMATE
 */
public record FriendsGoing(List<Friend> friends, Integer count, Integer affinity) {

    public static final int SHOWN = 5;
    public static final FriendsGoing HIDDEN = new FriendsGoing(null, null, null);

    public FriendsGoing {
        friends = friends == null ? null : List.copyOf(friends);
    }

    /** 0 for none, then 1 to 3 from FRIEND to INTIMATE. */
    public static int affinityOf(Closeness closeness) {
        return closeness == null ? 0 : closeness.ordinal() + 1;
    }
}
