package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.ports.EventRepository;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Users' display names, as this service knows them: the name on each user's BIRTHDAY, kept from
 * wely-users' USER_BIRTHDAY. Signup asks for it, so every active user has one.
 */
public class DisplayNames {

    /** Shown when no name is known. */
    public static final String FALLBACK = "Un ami";

    private final EventRepository eventRepository;

    public DisplayNames(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** Each user's name, in one read; a user without one is left out, see {@link #nameIn}. */
    public Mono<Map<String, String>> of(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        return eventRepository.findBirthdaysOf(userIds)
                .filter(birthday -> birthday.getHonoreeName() != null && !birthday.getHonoreeName().isBlank())
                .collectMap(Event::getOrganizerId, Event::getHonoreeName);
    }

    public Mono<String> of(String userId) {
        return of(List.of(userId)).map(names -> nameIn(names, userId));
    }

    /** The user's name among those read, or {@link #FALLBACK}. */
    public static String nameIn(Map<String, String> names, String userId) {
        return Objects.requireNonNullElse(names.get(userId), FALLBACK);
    }
}
