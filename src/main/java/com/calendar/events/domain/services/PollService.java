package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Date polls: voting, fixing the date, and watching the votes live. Who may see or act on an
 * event is decided by {@link EventService}, so the rules stay in one place.
 */
public class PollService {

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final PollUpdates pollUpdates;

    public PollService(EventRepository eventRepository, EventService eventService, PollUpdates pollUpdates) {
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.pollUpdates = pollUpdates;
    }

    /**
     * Replaces the voter's ballot; anyone who may see the event may vote, as anyone may join it.
     * An empty ballot withdraws the vote.
     */
    public Mono<Poll> vote(String eventId, String voterId, Map<String, PollAnswer> answers) {
        return eventService.getEventById(eventId, voterId)
                .flatMap(event -> {
                    if (!event.isPolling()) {
                        return Mono.error(new EventException(EventErrorCode.POLL_CLOSED));
                    }
                    if (!event.getPoll().acceptsBallot(answers)) {
                        return Mono.error(new EventException(EventErrorCode.POLL_SLOT_UNKNOWN));
                    }
                    return eventRepository.castBallot(eventId, voterId, answers)
                            // Fixed between the read and the write.
                            .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.POLL_CLOSED)));
                })
                .map(Event::getPoll)
                .doOnNext(poll -> pollUpdates.publish(eventId, poll));
    }

    /**
     * Fixes the date on one of the proposed slots, for the organizer only. Those who answered yes
     * for it are subscribed. The yes voters come from the read: a vote landing between the read
     * and the write still counts in the poll, but does not subscribe its author.
     */
    public Mono<Event> fix(String eventId, String organizerId, String slotId) {
        return eventService.requireOrganizer(eventId, organizerId)
                .flatMap(event -> {
                    if (!event.isPolling()) {
                        return Mono.error(new EventException(EventErrorCode.POLL_CLOSED));
                    }
                    Poll poll = event.getPoll();
                    PollSlot slot = poll.slot(slotId).orElse(null);
                    if (slot == null) {
                        return Mono.error(new EventException(EventErrorCode.POLL_SLOT_UNKNOWN));
                    }
                    return eventRepository.fixPollDate(eventId, organizerId, slot, poll.yesVotersOf(slotId))
                            .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.POLL_CLOSED)));
                })
                .doOnNext(fixed -> pollUpdates.publish(eventId, fixed.getPoll()));
    }

    /** Open polls the user organizes, takes part in or voted on: what their calendar shows dashed. */
    public Flux<Event> getMyPolls(String userId) {
        return eventRepository.findOpenPollsInvolving(userId);
    }

    /**
     * The poll's state now, then every change, ending with the closed state once the date is fixed.
     * The outer Mono settles access first, so a refusal is an error before anything is streamed.
     */
    public Mono<Flux<Poll>> watch(String eventId, String viewerId) {
        return eventService.getEventById(eventId, viewerId)
                .flatMap(event -> event.getPoll() == null
                        ? Mono.error(new EventException(EventErrorCode.POLL_CLOSED))
                        : Mono.just(liveStates(eventId)));
    }

    /**
     * Live updates are subscribed before the current state is read, so no vote falls in between.
     * If one arrives first it is newer than any read still in flight, which is then dropped.
     */
    private Flux<Poll> liveStates(String eventId) {
        return Flux.defer(() -> {
            AtomicBoolean liveSeen = new AtomicBoolean();
            Flux<Poll> live = pollUpdates.stream(eventId).doOnNext(poll -> liveSeen.set(true));
            Mono<Poll> current = eventRepository.findById(eventId)
                    .mapNotNull(Event::getPoll)
                    .filter(poll -> !liveSeen.get());
            return Flux.merge(live, current)
                    .takeUntil(poll -> !poll.isOpen());
        });
    }
}
