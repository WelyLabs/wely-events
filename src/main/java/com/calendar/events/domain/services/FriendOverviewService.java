package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendOverview;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/**
 * A friend's page: what the two share, and what the friend has coming that the caller could join.
 *
 * <p>"Both on it" is filtered by the database; whether the caller may see one of the friend's events
 * depends on the caller's own friends, so it is decided here, with {@code Event#isVisibleTo}.
 */
public class FriendOverviewService {

    public static final int UPCOMING_TOGETHER_SIZE = 5;
    public static final int MEMORIES_TOGETHER_SIZE = 6;
    public static final int FRIEND_UPCOMING_SIZE = 5;

    private final EventRepository eventRepository;
    private final SocialGraph socialGraph;
    private final Clock clock;

    public FriendOverviewService(EventRepository eventRepository, SocialGraph socialGraph, Clock clock) {
        this.eventRepository = eventRepository;
        this.socialGraph = socialGraph;
        this.clock = clock;
    }

    public Mono<FriendOverview> overviewOf(String viewerId, String friendId) {
        // Same refusal as a friend's calendar asked for oneself.
        if (viewerId.equals(friendId)) {
            return Mono.error(new EventException(EventErrorCode.INVALID_CALENDAR_REQUEST));
        }

        Mono<Closeness> friendship = socialGraph.closenessBetween(viewerId, friendId)
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.NOT_A_FRIEND)));
        Mono<Set<String>> viewerFriends = socialGraph.friendIdsOf(viewerId).defaultIfEmpty(Set.of());

        return Mono.zip(friendship, viewerFriends)
                .flatMap(known -> overviewOf(viewerId, friendId, known.getT2(), clock.instant()));
    }

    private Mono<FriendOverview> overviewOf(String viewerId, String friendId, Set<String> viewerFriends, Instant now) {
        return Mono.zip(
                eventRepository.countPastInvolvingBoth(viewerId, friendId, now),
                eventRepository.findUpcomingInvolvingBoth(viewerId, friendId, now, UPCOMING_TOGETHER_SIZE).collectList(),
                eventRepository.findPastInvolvingBoth(viewerId, friendId, now, MEMORIES_TOGETHER_SIZE).collectList(),
                // Read soonest first and stopped at the fifth match: the rest of the friend's agenda is never loaded.
                eventRepository.findUpcomingInvolving(friendId, viewerId, now)
                        .filter(event -> !event.involves(viewerId) && event.isVisibleTo(viewerId, viewerFriends))
                        .take(FRIEND_UPCOMING_SIZE)
                        .collectList())
                .map(found -> new FriendOverview(Math.toIntExact(found.getT1()),
                        found.getT2(), found.getT3(), found.getT4()));
    }
}
