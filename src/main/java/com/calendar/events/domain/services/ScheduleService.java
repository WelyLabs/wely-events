package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Schedule conflicts: which of the user's events overlap a time, where an event could move to,
 * and moving it.
 *
 * <p>Anyone may be told that an event clashes with their own agenda; only the organizer may move
 * it. Who may see an event is decided by {@link EventService}, so the rule stays in one place.
 */
public class ScheduleService {

    /** As for free slots: no conflict search or date wider than a month grid. */
    static final Duration MAX_RANGE = Duration.ofDays(62);
    /** Proposals start at most a day before the event, and at most a week after. */
    static final Duration LOOK_BEHIND = Duration.ofDays(1);
    static final Duration LOOK_AHEAD = Duration.ofDays(7);
    static final int MAX_PROPOSALS = 3;

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final Clock clock;
    private final ZoneId zone;

    public ScheduleService(EventRepository eventRepository, EventService eventService, Clock clock, ZoneId zone) {
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * The user's events, organized or joined, that overlap {@code range}, leaving out
     * {@code excludedEventId} (null for none): what a new event, not saved yet, would clash with.
     */
    public Flux<Event> conflictsWith(String userId, TimeRange range, String excludedEventId) {
        if (range.duration().compareTo(MAX_RANGE) > 0) {
            return Flux.error(new EventException(EventErrorCode.SCHEDULE_INVALID));
        }
        return eventRepository.findOccupying(Set.of(userId), range.start(), range.end(), userId)
                .filter(event -> !Objects.equals(event.getId(), excludedEventId));
    }

    /**
     * The viewer's events that overlap an event they may see, whether they take part or are
     * thinking of joining. An open date poll has no date yet: it clashes with nothing.
     */
    public Flux<Event> conflictsOf(String eventId, String viewerId) {
        return eventService.getEventById(eventId, viewerId)
                .filter(event -> !event.isPolling())
                .flatMapMany(event -> conflictsWith(viewerId, rangeOf(event), event.getId()));
    }

    /**
     * Up to {@link #MAX_PROPOSALS} times, for the organizer only, when the organizer and everyone
     * taking part are free for as long as the event lasts, closest to its current time first.
     */
    public Flux<TimeRange> proposalsFor(String eventId, String organizerId) {
        return requireMovable(eventId, organizerId)
                .flatMapMany(event -> {
                    TimeRange original = rangeOf(event);
                    Instant now = clock.instant();
                    Instant from = later(now, original.start().minus(LOOK_BEHIND));
                    Instant to = later(now, original.start()).plus(LOOK_AHEAD).plus(original.duration());
                    TimeRange window = new TimeRange(from, to);
                    Set<String> everyone = new HashSet<>(event.getParticipantIds());
                    everyone.add(event.getOrganizerId());
                    return eventRepository.findOccupying(everyone, window.start(), window.end(), organizerId)
                            .filter(other -> !Objects.equals(other.getId(), event.getId()))
                            .map(ScheduleService::rangeOf)
                            .collectList()
                            .flatMapIterable(busy -> RescheduleFinder.proposals(original, busy, window, zone, MAX_PROPOSALS));
                });
    }

    /**
     * Moves the event to {@code range}, for the organizer only. When others take part, the move is
     * flagged in the same write for wely-chat to announce in the event's group. The write matches
     * the start that was read, so two moves at once cannot both land.
     */
    public Mono<Event> reschedule(String eventId, String organizerId, TimeRange range) {
        if (range.duration().compareTo(MAX_RANGE) > 0 || range.start().isBefore(clock.instant())) {
            return Mono.error(new EventException(EventErrorCode.SCHEDULE_INVALID));
        }
        return requireMovable(eventId, organizerId)
                .flatMap(event -> {
                    if (range.start().equals(event.getStartDate()) && range.end().equals(event.getEndDate())) {
                        return Mono.just(event);
                    }
                    boolean shared = event.getParticipantIds().stream().anyMatch(id -> !id.equals(organizerId));
                    return eventRepository.reschedule(event, range, shared)
                            .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.SCHEDULE_CHANGED)));
                });
    }

    /** The organizer's event, refused while its date poll is open: the poll decides the date then. */
    private Mono<Event> requireMovable(String eventId, String organizerId) {
        return eventService.requireOrganizer(eventId, organizerId)
                .flatMap(event -> event.isPolling()
                        ? Mono.error(new EventException(EventErrorCode.SCHEDULE_LOCKED))
                        : Mono.just(event));
    }

    private static TimeRange rangeOf(Event event) {
        return new TimeRange(event.getStartDate(), event.effectiveEnd());
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
