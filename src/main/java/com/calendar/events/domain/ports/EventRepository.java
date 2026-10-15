package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.TimeRange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

public interface EventRepository {
    Flux<Event> findAll();

    Mono<Event> findById(String id);

    Mono<Event> save(Event event);

    /** Deletes the event if this user organizes it, and returns it as it was; empty when nothing matched. */
    Mono<Event> deleteByOrganizer(String eventId, String organizerId);

    /**
     * Deletes the event only if this user organizes it and nobody else takes part, in one conditional
     * write, and returns it as it was. Empty when nothing matched, e.g. someone joined meanwhile.
     */
    Mono<Event> deleteIfAlone(String eventId, String organizerId);

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

    /**
     * Removes the user from the event's participants, idempotently, unless they organize it: an
     * organizer leaves through {@link #handOver} or {@link #deleteIfAlone}. Returns the event as it
     * stands after the write.
     */
    Mono<Event> removeParticipant(String eventId, String userId);

    /**
     * Makes {@code newOrganizerId} the organizer and removes the current one from the participants,
     * in one write conditioned on both still holding their place. Empty when nothing matched.
     */
    Mono<Event> handOver(String eventId, String organizerId, String newOrganizerId);

    /**
     * Events that keep one of these users busy between {@code from} and {@code to}: those they
     * take part in or organize, overlapping the window. An event without an end lasts
     * {@link Event#DEFAULT_DURATION}. An event whose date poll is open keeps nobody busy.
     */
    Flux<Event> findOccupying(Collection<String> userIds, Instant from, Instant to);

    /** Sets the event's cover reference (null clears it); empty when no event by this organizer has this id. */
    Mono<Event> setImage(String eventId, String organizerId, String image);

    /** Every event the user organizes or takes part in, past and future. */
    Flux<Event> findInvolving(String userId);

    /**
     * Events not over yet at {@code now} that the user organizes or takes part in, soonest first.
     * An event without an end lasts {@link Event#DEFAULT_DURATION}.
     */
    Flux<Event> findUpcomingInvolving(String userId, Instant now);

    /** Events not over yet at {@code now} that both users are on, soonest first, at most {@code limit}. */
    Flux<Event> findUpcomingInvolvingBoth(String userId, String otherId, Instant now, int limit);

    /** Events over before {@code now} that both users were on, most recent first, at most {@code limit}. */
    Flux<Event> findPastInvolvingBoth(String userId, String otherId, Instant now, int limit);

    /** How many events over before {@code now} both users were on. */
    Mono<Long> countPastInvolvingBoth(String userId, String otherId, Instant now);

    /**
     * Replaces the voter's whole ballot in one atomic write, leaving the others untouched; an empty
     * map withdraws it. Empty when the event has no open poll.
     */
    Mono<Event> castBallot(String eventId, String voterId, Map<String, PollAnswer> answers);

    /**
     * Closes the open poll on {@code slot} in one compare-and-set: dates moved to the slot, the yes
     * voters added to the participants, the group and the date announcement flagged for their
     * relays. Empty when no open poll by this organizer matched, e.g. it was fixed meanwhile.
     */
    Mono<Event> fixPollDate(String eventId, String organizerId, PollSlot slot, Collection<String> yesVoterIds);

    /**
     * Moves the event, as it was read, to {@code range} in one compare-and-set on its organizer and
     * its start, unless a date poll is open. With {@code announce}, the same write flags the move
     * for the date relay, keeping the read dates for the announcement. Empty when nothing matched,
     * e.g. the date changed meanwhile.
     */
    Mono<Event> reschedule(Event read, TimeRange range, boolean announce);

    /** Events with an open poll that the user organizes, takes part in or voted on. */
    Flux<Event> findOpenPollsInvolving(String userId);
}
