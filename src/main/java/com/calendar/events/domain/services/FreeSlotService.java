package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendDirectory;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds when a user and some of their friends are all free.
 *
 * <p>Only friends can be searched: anyone else's schedule is none of the requester's business.
 * The answer holds free ranges only, never the events behind the busy ones.
 */
public class FreeSlotService {

    /** Proposed slots start on a quarter hour, like the calendar's selection. */
    static final Duration GRID = Duration.ofMinutes(15);
    static final Duration MIN_DURATION = Duration.ofMinutes(15);
    /** A month grid shows six weeks: 62 days covers it, as for a friend's calendar. */
    static final Duration MAX_WINDOW = Duration.ofDays(62);
    static final int MAX_FRIENDS = 20;

    private final EventRepository eventRepository;
    private final FriendDirectory friendDirectory;

    public FreeSlotService(EventRepository eventRepository, FriendDirectory friendDirectory) {
        this.eventRepository = eventRepository;
        this.friendDirectory = friendDirectory;
    }

    /** Ranges of at least {@code duration} inside {@code window} where nobody is busy. */
    public Flux<TimeRange> findCommonFreeRanges(String requesterId, Set<String> friendIds,
                                                TimeRange window, Duration duration) {
        if (!isValidSearch(requesterId, friendIds, window, duration)) {
            return Flux.error(new EventException(EventErrorCode.INVALID_SLOT_SEARCH));
        }

        Set<String> everyone = new HashSet<>(friendIds);
        everyone.add(requesterId);

        return friendDirectory.findFriendIds(requesterId)
                // Fails closed: no answer is treated as no friends, not as permission.
                .defaultIfEmpty(Set.of())
                .flatMap(friends -> friends.containsAll(friendIds)
                        ? Mono.just(friends)
                        : Mono.error(new EventException(EventErrorCode.NOT_A_FRIEND)))
                // Deferred: passed directly, findOccupying would be called while the chain is
                // assembled, before the friendship check had run.
                .thenMany(Flux.defer(() -> eventRepository.findOccupying(everyone, window.start(), window.end())))
                .map(event -> new TimeRange(event.getStartDate(), event.effectiveEnd()))
                .collectList()
                .flatMapIterable(busy -> FreeSlotFinder.freeRanges(window, busy, duration, GRID));
    }

    private static boolean isValidSearch(String requesterId, Set<String> friendIds,
                                         TimeRange window, Duration duration) {
        return friendIds != null
                && !friendIds.isEmpty()
                && friendIds.size() <= MAX_FRIENDS
                && !friendIds.contains(requesterId)
                && duration != null
                && duration.compareTo(MIN_DURATION) >= 0
                && duration.compareTo(window.duration()) <= 0
                && window.duration().compareTo(MAX_WINDOW) <= 0;
    }
}
