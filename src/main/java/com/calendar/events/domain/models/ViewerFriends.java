package com.calendar.events.domain.models;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The viewer's friends and how close each is, read once per response to tell who of them goes to each event. */
public record ViewerFriends(Map<String, Closeness> closenessByFriend) {

    public static final ViewerFriends NONE = new ViewerFriends(Map.of());

    public ViewerFriends {
        closenessByFriend = Map.copyOf(closenessByFriend);
    }

    public Set<String> friendIds() {
        return closenessByFriend.keySet();
    }

    /**
     * The viewer's friends among the organizer and participants. The viewer is never among their own
     * friends. A birthday shows none: nobody goes to it, and the feed never serves it.
     */
    public FriendsGoing friendsGoingTo(Event event) {
        if (event.isBirthday()) {
            return FriendsGoing.HIDDEN;
        }
        Set<String> people = new LinkedHashSet<>();
        if (event.getOrganizerId() != null) {
            people.add(event.getOrganizerId());
        }
        if (event.getParticipantIds() != null) {
            people.addAll(event.getParticipantIds());
        }
        List<Friend> going = new ArrayList<>();
        for (String userId : people) {
            Closeness closeness = closenessByFriend.get(userId);
            if (closeness != null) {
                going.add(new Friend(userId, closeness));
            }
        }
        // Stable: within a level, the organizer first, then join order.
        going.sort(Comparator.comparing(Friend::closeness).reversed());
        int affinity = going.isEmpty() ? 0 : FriendsGoing.affinityOf(going.getFirst().closeness());
        return new FriendsGoing(going.stream().limit(FriendsGoing.SHOWN).toList(), going.size(), affinity);
    }

    /** Events with the closest friends on them first, then the soonest. */
    public Comparator<Event> byAffinity() {
        return Comparator.comparingInt((Event event) -> affinityOf(event)).reversed()
                .thenComparing(Event::getStartDate, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private int affinityOf(Event event) {
        Integer affinity = friendsGoingTo(event).affinity();
        return affinity == null ? 0 : affinity;
    }
}
