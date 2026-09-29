package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;

    public Flux<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    public Mono<Event> getEventById(String id) {
        return eventRepository.findById(id);
    }

    public Mono<Event> createEvent(Event event) {
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

    public Mono<Event> toggleSubscription(String id, String userId) {
        return eventRepository.findById(id)
                .flatMap(event -> {
                    List<String> participants = event.getParticipantIds();
                    if (participants.contains(userId)) {
                        participants.remove(userId);
                    } else {
                        participants.add(userId);
                    }
                    return eventRepository.save(event);
                });
    }

    public Flux<Event> getSubscribedEvents(String userId) {
        return eventRepository.findByParticipantId(userId);
    }

    public Flux<Event> getFeedEvents(String userId) {
        return eventRepository.findByParticipantIdNot(userId);
    }
}
