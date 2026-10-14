package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Poll;
import reactor.core.publisher.Flux;

/** Live poll states, so those looking at an event see votes as they come. */
public interface PollUpdates {

    /** Hands the new state to whoever watches the event; nobody watching is fine. */
    void publish(String eventId, Poll poll);

    /** States published from subscription on; never completes by itself. */
    Flux<Poll> stream(String eventId);
}
