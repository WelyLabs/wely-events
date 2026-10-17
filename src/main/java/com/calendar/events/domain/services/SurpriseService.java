package com.calendar.events.domain.services;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Surprise birthday parties: organized by a close friend of the honoree, one per birthday, and kept
 * from the honoree by every query through {@code hiddenFrom}. Access afterwards is {@link EventService}'s.
 */
public class SurpriseService {

    /** Two surprises less than this far apart celebrate the same birthday. */
    static final Duration SAME_BIRTHDAY_WINDOW = Duration.ofDays(60);
    static final String EVENT_ID_PROPERTY = "eventId";

    private static final System.Logger LOG = System.getLogger(SurpriseService.class.getName());

    private final EventService eventService;
    private final EventRepository eventRepository;
    private final SocialGraph socialGraph;
    private final FriendDirectory friendDirectory;
    private final BirthdayNoticeOutbox noticeOutbox;
    private final Clock clock;
    private final ZoneId zone;

    public SurpriseService(EventService eventService, EventRepository eventRepository, SocialGraph socialGraph,
                           FriendDirectory friendDirectory, BirthdayNoticeOutbox noticeOutbox, Clock clock, ZoneId zone) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.socialGraph = socialGraph;
        this.friendDirectory = friendDirectory;
        this.noticeOutbox = noticeOutbox;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * Creates the surprise {@code event.organizerId} organizes for {@code event.honoreeId}, then tells
     * the honoree's other close friends, best effort. Refused for anyone but a close or intimate friend,
     * for an honoree without a birthday, and when a surprise already celebrates this birthday (its id
     * in the error's {@value #EVENT_ID_PROPERTY}).
     */
    public Mono<Event> createSurprise(Event event, List<PollSlot> candidateSlots, String organizerName) {
        String organizerId = event.getOrganizerId();
        String honoreeId = event.getHonoreeId();
        if (honoreeId == null || honoreeId.isBlank()) {
            return Mono.error(new EventException(EventErrorCode.EVENT_KIND_INVALID));
        }
        if (honoreeId.equals(organizerId)) {
            return Mono.error(new EventException(EventErrorCode.SURPRISE_NOT_ALLOWED));
        }
        return socialGraph.closenessBetween(organizerId, honoreeId)
                .filter(closeness -> closeness.compareTo(Closeness.CLOSE) >= 0)
                .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.SURPRISE_NOT_ALLOWED)))
                .then(Mono.defer(() -> eventRepository.findBirthdayOf(honoreeId)))
                .switchIfEmpty(Mono.error(() -> new EventException(EventErrorCode.NO_BIRTHDAY)))
                .flatMap(birthday -> eventService.prepare(asSurprise(event, birthday, organizerName), candidateSlots)
                        .flatMap(prepared -> insert(prepared, birthday.birthMonthDay()))
                        .flatMap(created -> announce(created, birthday.birthMonthDay()).thenReturn(created)));
    }

    private static Event asSurprise(Event event, Event birthday, String organizerName) {
        event.setKind(EventKind.SURPRISE);
        event.setVisibility(Visibility.FRIENDS);
        event.setCommunityId(null);
        event.setHiddenFrom(new ArrayList<>(List.of(event.getHonoreeId())));
        event.setHonoreeName(birthday.getHonoreeName());
        event.setOrganizerName(organizerName);
        event.setBirthMonth(null);
        event.setBirthDay(null);
        return event;
    }

    /** The window check names the existing surprise; the unique occurrence key settles two creations racing. */
    private Mono<Event> insert(Event surprise, MonthDay birthday) {
        int year = Event.nearestOccurrenceYear(birthday, surprise.getStartDate());
        surprise.setOccurrenceKey(Event.occurrenceKeyOf(surprise.getHonoreeId(), year));
        return eventRepository.findSurpriseStartingBetween(surprise.getHonoreeId(),
                        surprise.getStartDate().minus(SAME_BIRTHDAY_WINDOW), surprise.getStartDate().plus(SAME_BIRTHDAY_WINDOW))
                .flatMap(existing -> Mono.<Event>error(exists(existing)))
                .switchIfEmpty(Mono.defer(() -> eventRepository.insertSurprise(surprise)))
                .switchIfEmpty(Mono.defer(() -> eventRepository.findSurpriseByOccurrence(surprise.getOccurrenceKey())
                        .map(SurpriseService::exists)
                        .defaultIfEmpty(new EventException(EventErrorCode.SURPRISE_EXISTS))
                        .flatMap(Mono::error)));
    }

    private static EventException exists(Event existing) {
        return new EventException(EventErrorCode.SURPRISE_EXISTS, Map.of(EVENT_ID_PROPERTY, existing.getId()));
    }

    /** SURPRISE_CREATED to the honoree's close friends but the organizer; a failure costs the notices, not the surprise. */
    private Mono<Void> announce(Event surprise, MonthDay birthday) {
        String honoreeId = surprise.getHonoreeId();
        LocalDate date = birthday.atYear(Event.nearestOccurrenceYear(birthday, surprise.getStartDate()));
        int daysUntil = (int) Math.max(0, ChronoUnit.DAYS.between(clock.instant().atZone(zone).toLocalDate(), date));
        // The organizer's own list, fresh from their token, is one of those the honoree's friends are read from.
        return friendDirectory.refreshFriendsOf(surprise.getOrganizerId())
                .onErrorResume(error -> Mono.just(Map.of()))
                .then(Mono.defer(() -> friendDirectory.knownFriendsOf(honoreeId)))
                .flatMapMany(friends -> Flux.fromIterable(friends.entrySet()))
                .filter(friend -> friend.getValue().compareTo(Closeness.CLOSE) >= 0
                        && !friend.getKey().equals(surprise.getOrganizerId())
                        && !friend.getKey().equals(honoreeId))
                .concatMap(friend -> noticeOutbox.record(new BirthdayNotice(BirthdayNoticeType.SURPRISE_CREATED,
                        friend.getKey(), honoreeId, surprise.getHonoreeName(), date, daysUntil, friend.getValue(),
                        surprise.getId(), surprise.getOrganizerId(), surprise.getOrganizerName())))
                .then()
                .onErrorResume(error -> {
                    LOG.log(System.Logger.Level.WARNING, "Surprise {0} not announced: {1}", surprise.getId(), error.toString());
                    return Mono.empty();
                });
    }
}
