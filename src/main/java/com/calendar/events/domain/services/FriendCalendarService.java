package com.calendar.events.domain.services;

import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.Set;

/** A friend's calendar, disclosed according to how close the two are; see {@link CalendarDisclosure}. */
public class FriendCalendarService {

    static final Duration MAX_WINDOW = Duration.ofDays(62);

    private final EventRepository eventRepository;
    private final SocialGraph socialGraph;

    public FriendCalendarService(EventRepository eventRepository, SocialGraph socialGraph) {
        this.eventRepository = eventRepository;
        this.socialGraph = socialGraph;
    }

    public Flux<CalendarEntry> getFriendCalendar(String viewerId, String friendId, TimeRange window) {
        if (viewerId.equals(friendId) || window.duration().compareTo(MAX_WINDOW) > 0) {
            return Flux.error(new EventException(EventErrorCode.INVALID_CALENDAR_REQUEST));
        }

        // The closeness proves the friendship; the viewer's own friends decide which events they
        // may see anyway.
        Mono<Closeness> closeness = socialGraph.closenessBetween(viewerId, friendId)
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.NOT_A_FRIEND)));
        Mono<Set<String>> viewerFriends = socialGraph.friendIdsOf(viewerId).defaultIfEmpty(Set.of());

        return Mono.zip(closeness, viewerFriends)
                .flatMapMany(known -> eventRepository.findOccupying(Set.of(friendId), window.start(), window.end())
                        .map(event -> CalendarDisclosure.disclose(event, viewerId, known.getT2(), known.getT1())))
                .sort(Comparator.comparing(CalendarEntry::start));
    }
}
