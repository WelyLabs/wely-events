package com.calendar.events.domain.services;

import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.CircleEntry;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    /**
     * The calendars of all the viewer's intimate friends at once, disclosed as each one's own calendar is
     * to an intimate friend; one entry per intimate friend an event keeps busy. Never an event hidden from
     * the viewer. Soonest first.
     */
    public Flux<CircleEntry> getCircleCalendar(String viewerId, TimeRange window) {
        if (window.duration().compareTo(MAX_WINDOW) > 0) {
            return Flux.error(new BusinessException(BusinessErrorCode.INVALID_CIRCLE_REQUEST));
        }
        return socialGraph.friendsOf(viewerId)
                .defaultIfEmpty(Map.of())
                .flatMapMany(friends -> {
                    List<String> intimates = friends.entrySet().stream()
                            .filter(friend -> friend.getValue() == Closeness.INTIMATE)
                            .map(Map.Entry::getKey)
                            .sorted()
                            .toList();
                    if (intimates.isEmpty()) {
                        return Flux.<CircleEntry>empty();
                    }
                    return eventRepository.findOccupying(intimates, window.start(), window.end(), viewerId)
                            .flatMapIterable(event -> {
                                CalendarEntry entry = CalendarDisclosure.disclose(event, viewerId, friends.keySet(),
                                        Closeness.INTIMATE);
                                return intimates.stream()
                                        .filter(event::involves)
                                        .map(userId -> new CircleEntry(userId, entry))
                                        .toList();
                            });
                })
                .sort(Comparator.comparing((CircleEntry circle) -> circle.entry().start()).thenComparing(CircleEntry::userId));
    }

    public Flux<CalendarEntry> getFriendCalendar(String viewerId, String friendId, TimeRange window) {
        if (viewerId.equals(friendId) || window.duration().compareTo(MAX_WINDOW) > 0) {
            return Flux.error(new BusinessException(BusinessErrorCode.INVALID_CALENDAR_REQUEST));
        }

        // The closeness proves the friendship; the viewer's own friends decide which events they
        // may see anyway.
        Mono<Closeness> closeness = socialGraph.closenessBetween(viewerId, friendId)
                .switchIfEmpty(Mono.error(new BusinessException(BusinessErrorCode.NOT_A_FRIEND)));
        Mono<Set<String>> viewerFriends = socialGraph.friendIdsOf(viewerId).defaultIfEmpty(Set.of());

        return Mono.zip(closeness, viewerFriends)
                .flatMapMany(known -> eventRepository.findOccupying(Set.of(friendId), window.start(), window.end(), viewerId)
                        .map(event -> CalendarDisclosure.disclose(event, viewerId, known.getT2(), known.getT1())))
                .sort(Comparator.comparing(CalendarEntry::start));
    }
}
