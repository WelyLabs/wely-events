package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;

public interface EventRepository {
    Flux<Event> findAll();

    Mono<Event> findById(String id);

    Mono<Event> save(Event event);

    Mono<Void> deleteById(String id);

    Flux<Event> findByParticipantId(String userId);

    /**
     * Events the user does not take part in but may see: their own, public ones, and those shared
     * with the organizer's friends when the organizer is one of {@code friendIds}.
     */
    Flux<Event> findFeed(String userId, Collection<String> friendIds);

    /**
     * Events the user may see that are placed inside the box and end after {@code endingAfter}:
     * their own, those they take part in, public ones, and those shared by friends. Soonest first,
     * at most {@code limit}.
     */
    Flux<Event> findOnMap(String userId, Collection<String> friendIds, GeoBox box, Instant endingAfter, int limit);

    /**
     * Adds the user to the event's participants, idempotently, and returns the
     * updated event. Implementations must not read-modify-write the document:
     * two users subscribing at once would lose one of the two.
     */
    Mono<Event> addParticipant(String eventId, String userId);

    /** Removes the user from the event's participants, idempotently. */
    Mono<Event> removeParticipant(String eventId, String userId);

    /**
     * Events that keep one of these users busy between {@code from} and {@code to}: those they
     * take part in or organize, overlapping the window. An event without an end lasts
     * {@link Event#DEFAULT_DURATION}.
     */
    Flux<Event> findOccupying(Collection<String> userIds, Instant from, Instant to);
}
