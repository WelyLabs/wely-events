package com.calendar.events.domain.models;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * A user's friends and how close each is, as wely-social answered the user at {@code refreshedAt}.
 * Kept so the birthday reminders, which run without anyone's token, know who to tell.
 */
public record FriendList(String userId, Map<String, Closeness> friends, Instant refreshedAt) {

    public FriendList {
        friends = Map.copyOf(friends);
    }

    /**
     * The user's friends from every list that names them: closeness lives on the friendship, so a
     * friend's list says as much as the user's own. For each pair, the fresher of the two lists decides.
     */
    public static Map<String, Closeness> friendsOf(String userId, FriendList own, Collection<FriendList> naming) {
        Map<String, Closeness> friends = new HashMap<>();
        if (own != null) {
            friends.putAll(own.friends());
        }
        for (FriendList other : naming) {
            if (other.userId().equals(userId)) {
                continue;
            }
            boolean otherIsFresher = own == null || other.refreshedAt().isAfter(own.refreshedAt());
            Closeness closeness = other.friends().get(userId);
            if (!otherIsFresher) {
                continue;
            }
            if (closeness != null) {
                friends.put(other.userId(), closeness);
            } else {
                friends.remove(other.userId());
            }
        }
        return friends;
    }
}
