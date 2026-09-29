package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Event;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface EventRepository {
    Flux<Event> findAll();

    Mono<Event> findById(String id);

    Mono<Event> save(Event event);

    Mono<Void> deleteById(String id);

    Flux<Event> findByParticipantId(String userId);

    Flux<Event> findByParticipantIdNot(String userId);

    /**
     * Adds the user to the event's participants, idempotently, and returns the
     * updated event. Implementations must not read-modify-write the document:
     * two users subscribing at once would lose one of the two.
     */
    Mono<Event> addParticipant(String eventId, String userId);

    /** Removes the user from the event's participants, idempotently. */
    Mono<Event> removeParticipant(String eventId, String userId);
}
