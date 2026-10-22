package com.calendar.events.domain.models;

import java.util.List;
import java.util.Set;

/**
 * Who takes part in an event, as one viewer may see it, who is invited, and who asked to join: the
 * organizer sees every invitation and every request, the person concerned only their own, anyone
 * else none.
 */
public record Participants(int count, List<String> participantIds, List<String> friendsGoingIds,
                           List<String> invitedIds, List<String> requestedIds) {

    public Participants {
        participantIds = List.copyOf(participantIds);
        friendsGoingIds = List.copyOf(friendsGoingIds);
        invitedIds = List.copyOf(invitedIds);
        requestedIds = List.copyOf(requestedIds == null ? List.of() : requestedIds);
    }

    /** The full list only for those involved; anyone else sees how many and which friends. */
    public static Participants seenBy(Event event, String viewerId, Set<String> friendIdsOfViewer) {
        List<String> all = event.getParticipantIds();
        List<String> friends = all.stream()
                .filter(id -> !id.equals(viewerId) && friendIdsOfViewer.contains(id))
                .toList();
        return new Participants(all.size(), event.involves(viewerId) ? all : List.of(), friends,
                invitedSeenBy(event, viewerId), requestedSeenBy(event, viewerId));
    }

    /** Who asked to join: the organizer's to answer, and otherwise only one's own request. */
    private static List<String> requestedSeenBy(Event event, String viewerId) {
        List<String> requested = event.getRequestedIds() == null ? List.of() : event.getRequestedIds();
        if (viewerId.equals(event.getOrganizerId())) {
            return requested;
        }
        return requested.contains(viewerId) ? List.of(viewerId) : List.of();
    }

    private static List<String> invitedSeenBy(Event event, String viewerId) {
        List<String> invited = event.getInvitedIds() == null ? List.of() : event.getInvitedIds();
        if (viewerId.equals(event.getOrganizerId())) {
            return invited;
        }
        return invited.contains(viewerId) ? List.of(viewerId) : List.of();
    }
}
