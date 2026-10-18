package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventCover;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipMemories;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/** What two friends shared, for the screen a friendship anniversary opens. Only ever about the caller's own friend. */
public class FriendshipMemoriesService {

    public static final int HIGHLIGHTS_SIZE = 6;

    /** Those with a cover first, then the most recent. */
    private static final Comparator<Event> HIGHLIGHTS_ORDER =
            Comparator.comparing((Event event) -> EventCover.of(event).reference() == null)
                    .thenComparing(Event::getStartDate, Comparator.reverseOrder());

    private final EventRepository eventRepository;
    private final FriendshipDirectory friendships;
    private final DisplayNames displayNames;
    private final Clock clock;
    private final ZoneId zone;

    public FriendshipMemoriesService(EventRepository eventRepository, FriendshipDirectory friendships,
                                     DisplayNames displayNames, Clock clock, ZoneId zone) {
        this.eventRepository = eventRepository;
        this.friendships = friendships;
        this.displayNames = displayNames;
        this.clock = clock;
        this.zone = zone;
    }

    /** 404 for anyone but a current friend; never an outing hidden from either of the two. */
    public Mono<FriendshipMemories> memoriesOf(String userId, String friendId) {
        Instant now = clock.instant();
        return friendships.pairOf(userId, friendId)
                .filter(Friendship::friends)
                .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.FRIEND_NOT_FOUND)))
                .flatMap(friendship -> Mono.zip(
                                eventRepository.findSharedOutings(userId, friendId, now).collectList(),
                                displayNames.of(friendId))
                        .map(found -> memories(friendship, friendId, found.getT2(), found.getT1(), now)));
    }

    /** {@code outings} come most recent first. */
    private FriendshipMemories memories(Friendship friendship, String friendId, String name, List<Event> outings,
                                        Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant since = friendship.since();
        LocalDate start = since == null ? today : since.atZone(zone).toLocalDate();
        return new FriendshipMemories(friendId, name, friendship.closeness(), since,
                Math.max(0, Period.between(start, today).getYears()),
                Math.max(0, ChronoUnit.DAYS.between(start, today)),
                outings.size(),
                outings.isEmpty() ? null : outings.getLast(),
                outings.isEmpty() ? null : outings.getFirst(),
                outings.stream().sorted(HIGHLIGHTS_ORDER).limit(HIGHLIGHTS_SIZE).toList());
    }
}
