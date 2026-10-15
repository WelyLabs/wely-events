package com.calendar.events.domain.models;

/** What erasing a deleted account changed, for the logs and the tests. */
public record ErasureReport(long ballotsWithdrawn, long eventsHandedOver, long eventsDeleted, long eventsLeft,
                           long responsesCleared, long receiptsDropped) {

    public static final ErasureReport NONE = new ErasureReport(0, 0, 0, 0, 0, 0);

    public ErasureReport withBallotsWithdrawn(long count) {
        return new ErasureReport(count, eventsHandedOver, eventsDeleted, eventsLeft, responsesCleared, receiptsDropped);
    }

    public ErasureReport withReleased(long handedOver, long deleted) {
        return new ErasureReport(ballotsWithdrawn, handedOver, deleted, eventsLeft, responsesCleared, receiptsDropped);
    }

    public ErasureReport withEventsLeft(long count) {
        return new ErasureReport(ballotsWithdrawn, eventsHandedOver, eventsDeleted, count, responsesCleared, receiptsDropped);
    }

    public ErasureReport withResponsesCleared(long count) {
        return new ErasureReport(ballotsWithdrawn, eventsHandedOver, eventsDeleted, eventsLeft, count, receiptsDropped);
    }

    public ErasureReport withReceiptsDropped(long count) {
        return new ErasureReport(ballotsWithdrawn, eventsHandedOver, eventsDeleted, eventsLeft, responsesCleared, count);
    }
}
