package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Poll;
import reactor.core.publisher.Flux;

/** Live poll states, so those looking at an event see votes as they come. */
public interface PollUpdates {

    /** Hands the new state to whoever watches the event; nobody watching is fine. */
    void publish(String eventId, Poll poll);

    /** States published from subscription on; completes only when the viewer is disconnected. */
    Flux<Poll> stream(String eventId, String viewerId);

    /** Completes every stream the viewer has open, on any event; nobody watching is fine. */
    void disconnect(String viewerId);
}
