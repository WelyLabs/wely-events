package com.calendar.events.domain.models;

import java.util.List;
import java.util.Map;

/**
 * Who said what to a community event. Everyone taking part counts as YES, the organizer first; an
 * answer kept from before someone joined does not count twice.
 */
public record EventResponses(List<String> yes, List<String> maybe, List<String> no) {

    public EventResponses {
        yes = List.copyOf(yes);
        maybe = List.copyOf(maybe);
        no = List.copyOf(no);
    }

    /** Null for an event outside any community: nobody answers those, one takes part or not. */
    public static EventResponses of(Event event) {
        if (!event.isCommunityEvent()) {
            return null;
        }
        List<String> going = event.getParticipantIds();
        List<String> maybe = answered(event, PollAnswer.MAYBE);
        List<String> no = answered(event, PollAnswer.NO);
        return new EventResponses(going, maybe, no);
    }

    /** Null on an event read from before answers existed. */
    private static List<String> answered(Event event, PollAnswer answer) {
        Map<String, PollAnswer> responses = event.getResponses() == null ? Map.of() : event.getResponses();
        return responses.entrySet().stream()
                .filter(entry -> entry.getValue() == answer && !event.getParticipantIds().contains(entry.getKey()))
                .map(Map.Entry::getKey)
                .toList();
    }
}
