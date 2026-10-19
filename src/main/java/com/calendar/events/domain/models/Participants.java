package com.calendar.events.domain.models;

import java.util.List;
import java.util.Set;

/**
 * Who takes part in an event, as one viewer may see it, and who is invited: the organizer sees
 * every invitation, an invitee only their own, anyone else none.
 */
public record Participants(int count, List<String> participantIds, List<String> friendsGoingIds,
                           List<String> invitedIds) {

    public Participants {
        participantIds = List.copyOf(participantIds);
        friendsGoingIds = List.copyOf(friendsGoingIds);
        invitedIds = List.copyOf(invitedIds);
    }

    /** The full list only for those involved; anyone else sees how many and which friends. */
    public static Participants seenBy(Event event, String viewerId, Set<String> friendIdsOfViewer) {
        List<String> all = event.getParticipantIds();
        List<String> friends = all.stream()
                .filter(id -> !id.equals(viewerId) && friendIdsOfViewer.contains(id))
                .toList();
        return new Participants(all.size(), event.involves(viewerId) ? all : List.of(), friends,
                invitedSeenBy(event, viewerId));
    }

    private static List<String> invitedSeenBy(Event event, String viewerId) {
        List<String> invited = event.getInvitedIds() == null ? List.of() : event.getInvitedIds();
        if (viewerId.equals(event.getOrganizerId())) {
            return invited;
        }
        return invited.contains(viewerId) ? List.of(viewerId) : List.of();
    }
}
