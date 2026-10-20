package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Date polls: voting, fixing the date, and watching the votes live; access rules stay in {@link EventService}. */
public class PollService {

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final PollUpdates pollUpdates;
    private final AccountGuard accountGuard;

    public PollService(EventRepository eventRepository, EventService eventService, PollUpdates pollUpdates,
                       AccountGuard accountGuard) {
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.pollUpdates = pollUpdates;
        this.accountGuard = accountGuard;
    }

    /**
     * Replaces the voter's ballot; anyone who may see the event may vote, as anyone may join it.
     * An empty ballot withdraws the vote.
     */
    public Mono<Poll> vote(String eventId, String voterId, Map<String, PollAnswer> answers) {
        return eventService.getEventById(eventId, voterId)
                .flatMap(event -> {
                    if (!event.isPolling()) {
                        return Mono.error(new BusinessException(BusinessErrorCode.POLL_CLOSED));
                    }
                    if (!event.getPoll().acceptsBallot(answers)) {
                        return Mono.error(new BusinessException(BusinessErrorCode.POLL_SLOT_UNKNOWN));
                    }
                    return eventRepository.castBallot(eventId, voterId, answers)
                            // Fixed between the read and the write.
                            .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.POLL_CLOSED)));
                })
                .map(Event::getPoll)
                .doOnNext(poll -> pollUpdates.publish(eventId, poll));
    }

    /** Fixes the date on a slot, for the organizer only, subscribing the yes voters read, deleted accounts excepted. */
    public Mono<Event> fix(String eventId, String organizerId, String slotId) {
        return eventService.requireOrganizer(eventId, organizerId)
                .flatMap(event -> {
                    if (!event.isPolling()) {
                        return Mono.error(new BusinessException(BusinessErrorCode.POLL_CLOSED));
                    }
                    Poll poll = event.getPoll();
                    PollSlot slot = poll.slot(slotId).orElse(null);
                    if (slot == null) {
                        return Mono.error(new BusinessException(BusinessErrorCode.POLL_SLOT_UNKNOWN));
                    }
                    if (eventService.isInPast(false, slot.start(), slot.end())) {
                        // A slot gone by while the poll stayed open: the date is fixed on one to come.
                        return Mono.error(new BusinessException(BusinessErrorCode.EVENT_IN_PAST));
                    }
                    List<String> yesVoters = poll.yesVotersOf(slotId);
                    return accountGuard.deletedAmong(yesVoters)
                            .flatMap(deleted -> eventRepository.fixPollDate(eventId, organizerId, slot,
                                    yesVoters.stream().filter(voter -> !deleted.contains(voter)).toList()))
                            .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.POLL_CLOSED)));
                })
                .doOnNext(fixed -> pollUpdates.publish(eventId, fixed.getPoll()));
    }

    /** Open polls the user organizes, takes part in or voted on: what their calendar shows dashed. */
    public Flux<Event> getMyPolls(String userId) {
        return eventRepository.findOpenPollsInvolving(userId);
    }

    /** The poll's state now, then every change until it closes, once access is settled; a deleted viewer is refused. */
    public Mono<Flux<Poll>> watch(String eventId, String viewerId) {
        return accountGuard.requireLive(viewerId)
                .then(Mono.defer(() -> eventService.getEventById(eventId, viewerId)))
                .flatMap(event -> event.getPoll() == null
                        ? Mono.error(new BusinessException(BusinessErrorCode.POLL_CLOSED))
                        : Mono.just(liveStates(eventId, viewerId)));
    }

    /** Subscribes live updates before reading the current state, which is dropped if a live update beat it. */
    private Flux<Poll> liveStates(String eventId, String viewerId) {
        return Flux.defer(() -> {
            AtomicBoolean liveSeen = new AtomicBoolean();
            Sinks.Empty<Void> listening = Sinks.empty();
            Flux<Poll> live = pollUpdates.stream(eventId, viewerId)
                    .doOnSubscribe(subscription -> listening.tryEmitEmpty())
                    .doOnNext(poll -> liveSeen.set(true));
            Mono<Poll> current = eventRepository.findById(eventId)
                    .mapNotNull(Event::getPoll)
                    .filter(poll -> !liveSeen.get());
            // Checked again once live listens: an erasure in between disconnected nobody.
            Mono<Boolean> deletedMeanwhile = Mono.defer(() -> accountGuard.isDeleted(viewerId))
                    .filter(Boolean::booleanValue)
                    // takeUntilOther also stops on an empty completion: a live viewer must never signal.
                    .switchIfEmpty(Mono.never())
                    .delaySubscription(listening.asMono());
            return Flux.merge(live, current)
                    .takeUntil(poll -> !poll.isOpen())
                    .takeUntilOther(deletedMeanwhile);
        });
    }
}
