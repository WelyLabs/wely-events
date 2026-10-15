package com.calendar.events.domain.models;

/**
 * What leaving an event came to.
 *
 * @param deleted whether the user was the last member, which deleted the event
 * @param event   the event as it stands after the leave; null when it was deleted
 */
public record LeaveOutcome(boolean deleted, Event event) {

    public static LeaveOutcome left(Event event) {
        return new LeaveOutcome(false, event);
    }

    public static LeaveOutcome eventDeleted() {
        return new LeaveOutcome(true, null);
    }
}
