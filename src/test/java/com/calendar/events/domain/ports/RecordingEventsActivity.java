package com.calendar.events.domain.ports;

import java.util.concurrent.atomic.AtomicInteger;

/** Test double for {@link EventsActivity}: counts each fact reported, so a test can tell success from refusal. */
public class RecordingEventsActivity implements EventsActivity {

    public final AtomicInteger eventsCreated = new AtomicInteger();
    public final AtomicInteger invitationsSent = new AtomicInteger();
    public final AtomicInteger pollsCreated = new AtomicInteger();
    public final AtomicInteger pollVotes = new AtomicInteger();

    @Override
    public void eventCreated() {
        eventsCreated.incrementAndGet();
    }

    @Override
    public void invitationSent() {
        invitationsSent.incrementAndGet();
    }

    @Override
    public void pollCreated() {
        pollsCreated.incrementAndGet();
    }

    @Override
    public void pollVoted() {
        pollVotes.incrementAndGet();
    }

    /** Nothing reported at all. */
    public boolean isSilent() {
        return eventsCreated.get() + invitationsSent.get() + pollsCreated.get() + pollVotes.get() == 0;
    }
}
