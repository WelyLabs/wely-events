package com.calendar.events.domain.services;

import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Visibility;

import java.util.Set;

/**
 * Decides how much of one event a viewer sees in a friend's calendar.
 *
 * <ol>
 *   <li>An event the viewer may see anyway — taking part, public, or shared with the organizer's
 *       friends and the viewer is one — is shown in full: hiding it here would hide nothing.</li>
 *   <li>A private event only ever shows as busy, whatever the closeness.</li>
 *   <li>Anything else — typically an event of someone the viewer does not know — depends on
 *       how close the viewer is to the friend: busy, then the title, then everything.</li>
 * </ol>
 */
final class CalendarDisclosure {

    private CalendarDisclosure() {
    }

    static CalendarEntry disclose(Event event, String viewerId, Set<String> viewerFriendIds, Closeness closeness) {
        if (event.isVisibleTo(viewerId, viewerFriendIds)) {
            return full(event);
        }
        if (event.effectiveVisibility() == Visibility.PRIVATE) {
            return CalendarEntry.busy(event.getStartDate(), event.effectiveEnd());
        }
        return switch (closeness) {
            case FRIEND -> CalendarEntry.busy(event.getStartDate(), event.effectiveEnd());
            case CLOSE -> new CalendarEntry(event.getStartDate(), event.effectiveEnd(), null, event.getTitle(), null, null);
            case INTIMATE -> full(event);
        };
    }

    private static CalendarEntry full(Event event) {
        return new CalendarEntry(event.getStartDate(), event.effectiveEnd(), event.getId(),
                event.getTitle(), event.getLocation(), event.getDescription());
    }
}
