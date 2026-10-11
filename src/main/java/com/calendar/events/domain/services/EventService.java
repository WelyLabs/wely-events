package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendDirectory;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Set;

@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final FriendDirectory friendDirectory;

    public Flux<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    /** An event the viewer may not see answers as missing: its existence is not theirs to know. */
    public Mono<Event> getEventById(String id, String viewerId) {
        return eventRepository.findById(id)
                .flatMap(event -> requireVisible(event, viewerId))
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)));
    }

    public Mono<Event> createEvent(Event event) {
        if (event.getVisibility() == null) {
            event.setVisibility(Visibility.DEFAULT);
        }
        return eventRepository.save(event);
    }

    /**
     * Deletes an event on behalf of {@code requesterId}.
     *
     * <p>Only the organizer may delete an event. The ownership check happens here
     * rather than in the controller so the rule holds for every caller.
     */
    public Mono<Void> deleteEvent(String id, String requesterId) {
        return eventRepository.findById(id)
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)))
                .flatMap(event -> {
                    if (!Objects.equals(event.getOrganizerId(), requesterId)) {
                        return Mono.error(new EventException(EventErrorCode.EVENT_ACCESS_DENIED));
                    }
                    return eventRepository.deleteById(id);
                });
    }

    /**
     * Subscribes or unsubscribes the user, depending on their current state.
     *
     * <p>The read decides the direction, then a single idempotent write applies it.
     * The alternative — mutating the participant list and saving the event back —
     * loses a concurrent subscription from another user.
     */
    public Mono<Event> toggleSubscription(String id, String userId) {
        return eventRepository.findById(id)
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)))
                .flatMap(event -> event.getParticipantIds().contains(userId)
                        ? eventRepository.removeParticipant(id, userId)
                        // Joining needs to see the event first: no subscribing by guessed id.
                        : requireVisible(event, userId)
                                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)))
                                .flatMap(visible -> eventRepository.addParticipant(id, userId)));
    }

    public Flux<Event> getSubscribedEvents(String userId) {
        return eventRepository.findByParticipantId(userId);
    }

    /**
     * The events the user may see without taking part. If wely-social is down, the feed shrinks
     * to public events and the user's own rather than failing: nothing is shown that the
     * friendship would have been needed for.
     */
    public Flux<Event> getFeedEvents(String userId) {
        return friendDirectory.findFriendIds(userId)
                .defaultIfEmpty(Set.of())
                .onErrorResume(EventService::isSocialOutage, error -> Mono.just(Set.of()))
                .flatMapMany(friendIds -> eventRepository.findFeed(userId, friendIds));
    }

    /** The event if the viewer may see it, empty otherwise. Asks for friends only when it matters. */
    private Mono<Event> requireVisible(Event event, String viewerId) {
        if (event.isVisibleTo(viewerId, Set.of())) {
            return Mono.just(event);
        }
        if (event.effectiveVisibility() != Visibility.FRIENDS) {
            return Mono.empty();
        }
        return friendDirectory.findFriendIds(viewerId)
                .filter(friendIds -> event.isVisibleTo(viewerId, friendIds))
                .map(friendIds -> event);
    }

    private static boolean isSocialOutage(Throwable error) {
        return error instanceof EventException e && e.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE;
    }
}
