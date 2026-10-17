package com.calendar.events.domain.services;

import com.calendar.events.domain.models.BirthdayOccurrence;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Users' yearly birthdays: kept from wely-users, listed for a user and their friends, erased with the account. */
public class BirthdayService {

    /** A year and a month of margin, enough for any calendar view. */
    static final Duration MAX_RANGE = Duration.ofDays(400);
    /** A leap year, so that 29 February has a date: the stored dates are a placeholder, never the year of birth. */
    static final int PLACEHOLDER_YEAR = 2000;
    static final String TITLE_PREFIX = "Anniversaire de ";

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final FriendDirectory friendDirectory;
    private final BirthdayNoticeOutbox noticeOutbox;
    private final AccountGuard accountGuard;

    public BirthdayService(EventRepository eventRepository, EventService eventService, FriendDirectory friendDirectory,
                           BirthdayNoticeOutbox noticeOutbox, AccountGuard accountGuard) {
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.friendDirectory = friendDirectory;
        this.noticeOutbox = noticeOutbox;
        this.accountGuard = accountGuard;
    }

    /** Creates or updates the user's birthday; empty for a deleted account, which must not get one back. */
    public Mono<Event> recordBirthday(String userId, String name, MonthDay birthday) {
        return accountGuard.isDeleted(userId)
                .flatMap(deleted -> deleted
                        ? Mono.<Event>empty()
                        : eventRepository.upsertBirthday(birthdayEvent(userId, name, birthday)));
    }

    /** The user's one birthday event, all day on a placeholder year, for their friends. */
    public static Event birthdayEvent(String userId, String name, MonthDay birthday) {
        Instant start = birthday.atYear(PLACEHOLDER_YEAR).atStartOfDay(ZoneOffset.UTC).toInstant();
        return Event.builder()
                .kind(EventKind.BIRTHDAY)
                .title(TITLE_PREFIX + name)
                .organizerId(userId)
                .honoreeId(userId)
                .honoreeName(name)
                .birthMonth(birthday.getMonthValue())
                .birthDay(birthday.getDayOfMonth())
                .allDay(true)
                .startDate(start)
                .endDate(start.plus(Duration.ofDays(1)))
                .visibility(Visibility.FRIENDS)
                // Not joinable: nobody takes part in a birthday, not even its organizer.
                .participantIds(new ArrayList<>())
                .build();
    }

    /**
     * The viewer's birthday and their friends' falling in [from, to), each day by its UTC date, soonest
     * first. A social outage leaves the viewer's own only, as the feed falls back to what needs no friendship.
     */
    public Flux<BirthdayOccurrence> birthdaysFor(String viewerId, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            return Flux.error(new EventException(EventErrorCode.INVALID_BIRTHDAY_RANGE));
        }
        return friendDirectory.refreshFriendsOf(viewerId)
                .onErrorResume(BirthdayService::isSocialOutage, error -> Mono.just(Map.of()))
                .flatMapMany(friends -> {
                    Set<String> people = new HashSet<>(friends.keySet());
                    people.add(viewerId);
                    return eventRepository.findBirthdaysOf(people).collectList()
                            .flatMapMany(birthdays -> occurrences(viewerId, friends, birthdays, from, to));
                });
    }

    private Flux<BirthdayOccurrence> occurrences(String viewerId, Map<String, Closeness> friends, List<Event> birthdays,
                                                 Instant from, Instant to) {
        Map<String, Occurrence> byKey = new LinkedHashMap<>();
        for (Event birthday : birthdays) {
            for (LocalDate date : datesBetween(birthday.birthMonthDay(), from, to)) {
                Occurrence occurrence = new Occurrence(birthday, date);
                byKey.put(Event.occurrenceKeyOf(birthday.getOrganizerId(), date.getYear()), occurrence);
            }
        }
        // Never the viewer's own: a surprise for them is not theirs to know about.
        List<String> friendKeys = byKey.entrySet().stream()
                .filter(entry -> !entry.getValue().birthday().getOrganizerId().equals(viewerId))
                .map(Map.Entry::getKey)
                .toList();
        Mono<Map<String, Event>> surprises = friendKeys.isEmpty()
                ? Mono.just(Map.of())
                : eventRepository.findSurprisesFor(friendKeys, viewerId)
                        .collectMap(Event::getOccurrenceKey, Function.identity());
        return surprises.flatMapIterable(found -> byKey.entrySet().stream()
                .map(entry -> toOccurrence(viewerId, friends, entry.getValue(), found.get(entry.getKey())))
                .sorted(Comparator.comparing(BirthdayOccurrence::date).thenComparing(BirthdayOccurrence::name,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
    }

    private static BirthdayOccurrence toOccurrence(String viewerId, Map<String, Closeness> friends,
                                                   Occurrence occurrence, Event surprise) {
        String userId = occurrence.birthday().getOrganizerId();
        String name = occurrence.birthday().getHonoreeName();
        if (userId.equals(viewerId)) {
            return new BirthdayOccurrence(userId, name, occurrence.date(), true, null, false, null);
        }
        Closeness closeness = friends.get(userId);
        BirthdayOccurrence.Surprise shown = surprise == null ? null
                : new BirthdayOccurrence.Surprise(surprise.getId(), surprise.getOrganizerId(), surprise.involves(viewerId));
        boolean canOrganize = surprise == null && closeness != null && closeness.compareTo(Closeness.CLOSE) >= 0;
        return new BirthdayOccurrence(userId, name, occurrence.date(), false, closeness, canOrganize, shown);
    }

    /** The birthday's dates whose whole UTC day overlaps [from, to). */
    static List<LocalDate> datesBetween(MonthDay birthday, Instant from, Instant to) {
        if (birthday == null) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        int firstYear = LocalDate.ofInstant(from, ZoneOffset.UTC).getYear();
        int lastYear = LocalDate.ofInstant(to, ZoneOffset.UTC).getYear();
        for (int year = firstYear; year <= lastYear; year++) {
            LocalDate date = birthday.atYear(year);
            Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            if (dayStart.plus(Duration.ofDays(1)).isAfter(from) && dayStart.isBefore(to)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /**
     * Everything of an erased account's birthday: the birthday, every surprise for it (with what goes
     * with a deleted event), the remembered friend list and the notices not sent yet. Safe to replay.
     */
    public Mono<Long> forget(String userId) {
        return eventRepository.deleteBirthdayOf(userId)
                .thenMany(Flux.defer(() -> eventRepository.findSurprisesHonoring(userId)))
                .concatMap(surprise -> eventRepository.deleteByOrganizer(surprise.getId(), surprise.getOrganizerId())
                        .flatMap(gone -> eventService.afterDeletion(gone).thenReturn(gone)))
                .count()
                .flatMap(surprisesDeleted -> friendDirectory.forget(userId)
                        .then(Mono.defer(() -> noticeOutbox.forget(userId)))
                        .thenReturn(surprisesDeleted));
    }

    private static boolean isSocialOutage(Throwable error) {
        return error instanceof EventException e && e.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE;
    }

    private record Occurrence(Event birthday, LocalDate date) {
    }
}
