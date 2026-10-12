package com.calendar.events.domain.models;

import java.util.List;
import java.util.Set;

/** Who takes part in an event, as one viewer may see it. */
public record Participants(int count, List<String> participantIds, List<String> friendIds) {

    public Participants {
        participantIds = List.copyOf(participantIds);
        friendIds = List.copyOf(friendIds);
    }

    /** The full list only for those involved; anyone else sees how many and which friends. */
    public static Participants seenBy(Event event, String viewerId, Set<String> friendIdsOfViewer) {
        List<String> all = event.getParticipantIds();
        List<String> friends = all.stream()
                .filter(id -> !id.equals(viewerId) && friendIdsOfViewer.contains(id))
                .toList();
        return new Participants(all.size(), event.involves(viewerId) ? all : List.of(), friends);
    }
}
