package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventCover;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@RequiredArgsConstructor
public class EventService {

    /** Enough pins for a city; past this, the map asks the user to zoom in. */
    public static final int MAP_LIMIT = 200;

    private final EventRepository eventRepository;
    private final SocialGraph socialGraph;
    private final ExpenseRepository expenseRepository;
    private final PictureService pictureService;
    private final Clock clock;

    public Flux<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    /** An event the viewer may not see answers as missing: its existence is not theirs to know. */
    public Mono<Event> getEventById(String id, String viewerId) {
        return eventRepository.findById(id)
                .flatMap(event -> requireVisible(event, viewerId))
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)));
    }

    /** Who takes part in an event the viewer may see; a social outage just names no friend. */
    public Mono<Participants> getParticipants(String id, String viewerId) {
        return getEventById(id, viewerId)
                .flatMap(event -> socialGraph.friendIdsOf(viewerId)
                        .defaultIfEmpty(Set.of())
                        .onErrorResume(EventService::isSocialOutage, error -> Mono.just(Set.of()))
                        .map(friendIds -> Participants.seenBy(event, viewerId, friendIds)));
    }

    /** A new event has no cover: one is only ever uploaded, never pointed at by URL. */
    public Mono<Event> createEvent(Event event) {
        event.setImage(null);
        if (event.getVisibility() == null) {
            event.setVisibility(Visibility.DEFAULT);
        }
        return eventRepository.save(event);
    }

    /**
     * Deletes an event on behalf of {@code requesterId}.
     *
     * <p>Only the organizer may delete an event. The ownership check happens here
     * rather than in the controller so the rule holds for every caller. Its expenses go with it:
     * the event first, so a failure in between leaves stray expenses nobody can reach rather than
     * an event that lost its accounts. Its cover and receipts go last, best effort.
     */
    public Mono<Void> deleteEvent(String id, String requesterId) {
        return eventRepository.findById(id)
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)))
                .flatMap(event -> {
                    if (!Objects.equals(event.getOrganizerId(), requesterId)) {
                        return Mono.error(new EventException(EventErrorCode.EVENT_ACCESS_DENIED));
                    }
                    return eventRepository.deleteById(id)
                            // Receipts are read before their expenses go, or nothing would name them.
                            .then(Mono.defer(() -> expenseRepository.findByEvent(id)
                                    .filter(expense -> expense.receiptKey() != null)
                                    .map(Expense::receiptKey)
                                    .collectList()))
                            .flatMap(receipts -> expenseRepository.deleteByEvent(id).thenReturn(receipts))
                            .flatMap(receipts -> {
                                List<String> pictures = new ArrayList<>(receipts);
                                pictures.add(EventCover.of(event).storedKey());
                                return pictureService.discard(pictures);
                            });
                });
    }

    /**
     * Replaces the event's cover with an upload, for its organizer only. Stored first, so the event
     * never points at a missing object; if the event cannot take the new key, the object is removed
     * again. The previous cover goes last, best effort.
     */
    public Mono<Event> setCover(String id, String requesterId, byte[] content) {
        return requireOrganizer(id, requesterId)
                .flatMap(event -> pictureService.store(EventCover.newKey(event.getId()), content, MediaProfile.COVER)
                        .flatMap(stored -> eventRepository.setImage(event.getId(), requesterId, stored.key())
                                .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.EVENT_NOT_FOUND)))
                                .onErrorResume(error -> pictureService.discard(stored.key()).then(Mono.error(error))))
                        .flatMap(updated -> pictureService.discard(EventCover.of(event).storedKey()).thenReturn(updated)));
    }

    /** Clears the event's cover, for its organizer only; the stored object goes after, best effort. */
    public Mono<Void> removeCover(String id, String requesterId) {
        return requireOrganizer(id, requesterId)
                .flatMap(event -> eventRepository.setImage(event.getId(), requesterId, null)
                        .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.EVENT_NOT_FOUND)))
                        .flatMap(cleared -> pictureService.discard(EventCover.of(event).storedKey())));
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
        return socialGraph.friendIdsOf(userId)
                .defaultIfEmpty(Set.of())
                .onErrorResume(EventService::isSocialOutage, error -> Mono.just(Set.of()))
                .flatMapMany(friendIds -> eventRepository.findFeed(userId, friendIds));
    }

    /**
     * Upcoming and ongoing events the user may see, placed inside the area the map shows.
     * Like the feed, a social outage narrows it to what needs no friendship.
     */
    public Flux<Event> getMapEvents(String userId, GeoBox box) {
        return socialGraph.friendIdsOf(userId)
                .defaultIfEmpty(Set.of())
                .onErrorResume(EventService::isSocialOutage, error -> Mono.just(Set.of()))
                .flatMapMany(friendIds -> eventRepository.findOnMap(userId, friendIds, box, clock.instant(), MAP_LIMIT));
    }

    /** Not visible answers as missing, as everywhere; visible but someone else's is refused. */
    private Mono<Event> requireOrganizer(String id, String requesterId) {
        return getEventById(id, requesterId)
                .flatMap(event -> Objects.equals(event.getOrganizerId(), requesterId)
                        ? Mono.just(event)
                        : Mono.error(new EventException(EventErrorCode.EVENT_ACCESS_DENIED)));
    }

    /** The event if the viewer may see it, empty otherwise. Asks for friends only when it matters. */
    private Mono<Event> requireVisible(Event event, String viewerId) {
        if (event.isVisibleTo(viewerId, Set.of())) {
            return Mono.just(event);
        }
        if (event.effectiveVisibility() != Visibility.FRIENDS) {
            return Mono.empty();
        }
        return socialGraph.friendIdsOf(viewerId)
                .filter(friendIds -> event.isVisibleTo(viewerId, friendIds))
                .map(friendIds -> event);
    }

    private static boolean isSocialOutage(Throwable error) {
        return error instanceof EventException e && e.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE;
    }
}
