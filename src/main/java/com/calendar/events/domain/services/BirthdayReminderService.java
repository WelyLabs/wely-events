package com.calendar.events.domain.services;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The daily birthday reminders. Every notice due today or earlier and not recorded yet is recorded
 * for sending, under a key that makes a second run, a second pod or a catch-up run harmless:
 * <ul>
 *   <li>intimate friends from 21 days ahead, close friends from 14: a SURPRISE_PROPOSAL, naming the
 *       surprise if one exists, unless they already take part in it;</li>
 *   <li>every friend on the day: FRIEND_BIRTHDAY; the honoree on the day: BIRTHDAY_TODAY.</li>
 * </ul>
 * Friends are read from the {@link FriendshipDirectory}, wely-social's friendships as announced on
 * {@code FRIENDSHIP_CHANGED}: the job runs on nobody's behalf, without a token to ask wely-social with.
 */
public class BirthdayReminderService {

    static final int INTIMATE_LEAD_DAYS = 21;
    static final int CLOSE_LEAD_DAYS = 14;

    private static final System.Logger LOG = System.getLogger(BirthdayReminderService.class.getName());

    private final EventRepository eventRepository;
    private final FriendshipDirectory friendships;
    private final BirthdayNoticeOutbox noticeOutbox;
    private final AccountGuard accountGuard;
    private final Clock clock;
    private final ZoneId zone;

    public BirthdayReminderService(EventRepository eventRepository, FriendshipDirectory friendships,
                                   BirthdayNoticeOutbox noticeOutbox, AccountGuard accountGuard, Clock clock, ZoneId zone) {
        this.eventRepository = eventRepository;
        this.friendships = friendships;
        this.noticeOutbox = noticeOutbox;
        this.accountGuard = accountGuard;
        this.clock = clock;
        this.zone = zone;
    }

    /** One run; returns how many notices were newly recorded. One birthday failing never stops the others. */
    public Mono<Long> recordDueNotices() {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        return eventRepository.findAllBirthdays()
                .concatMap(birthday -> noticesFor(birthday, today)
                        .concatMap(noticeOutbox::record)
                        .filter(Boolean::booleanValue)
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Birthday notices of {0} not recorded: {1}",
                                    birthday.getOrganizerId(), error.toString());
                            return Flux.empty();
                        }))
                .count();
    }

    /** The notices due for this birthday's next occurrence, today included. */
    Flux<BirthdayNotice> noticesFor(Event birthday, LocalDate today) {
        MonthDay monthDay = birthday.birthMonthDay();
        if (monthDay == null) {
            return Flux.empty();
        }
        LocalDate next = nextOccurrence(monthDay, today);
        int daysUntil = (int) ChronoUnit.DAYS.between(today, next);
        if (daysUntil > INTIMATE_LEAD_DAYS) {
            return Flux.empty();
        }
        String honoreeId = birthday.getOrganizerId();
        Mono<Optional<Event>> surprise = eventRepository.findSurpriseByOccurrence(Event.occurrenceKeyOf(honoreeId, next.getYear()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
        Mono<Map<String, Closeness>> friends = friendships.friendsOf(honoreeId)
                .collectMap(Friend::userId, Friend::closeness);
        return Mono.zip(surprise, friends)
                .flatMap(found -> accountGuard.deletedAmong(found.getT2().keySet())
                        .map(deleted -> notices(birthday, next, daysUntil, found.getT1().orElse(null), found.getT2(), deleted)))
                .flatMapIterable(list -> list);
    }

    static LocalDate nextOccurrence(MonthDay monthDay, LocalDate today) {
        LocalDate thisYear = monthDay.atYear(today.getYear());
        return thisYear.isBefore(today) ? monthDay.atYear(today.getYear() + 1) : thisYear;
    }

    private static List<BirthdayNotice> notices(Event birthday, LocalDate date, int daysUntil, Event surprise,
                                                Map<String, Closeness> friends, Set<String> deleted) {
        String honoreeId = birthday.getOrganizerId();
        String name = birthday.getHonoreeName();
        List<BirthdayNotice> notices = new ArrayList<>();
        if (daysUntil == 0) {
            notices.add(new BirthdayNotice(BirthdayNoticeType.BIRTHDAY_TODAY, honoreeId, honoreeId, name, date, 0,
                    null, null, null, null));
        }
        friends.forEach((friendId, closeness) -> {
            if (friendId.equals(honoreeId) || deleted.contains(friendId)) {
                return;
            }
            if (daysUntil == 0) {
                notices.add(new BirthdayNotice(BirthdayNoticeType.FRIEND_BIRTHDAY, friendId, honoreeId, name, date, 0,
                        closeness, null, null, null));
            } else if (daysUntil <= leadDays(closeness) && (surprise == null || !surprise.involves(friendId))) {
                notices.add(new BirthdayNotice(BirthdayNoticeType.SURPRISE_PROPOSAL, friendId, honoreeId, name, date,
                        daysUntil, closeness,
                        surprise == null ? null : surprise.getId(),
                        surprise == null ? null : surprise.getOrganizerId(),
                        surprise == null ? null : surprise.getOrganizerName()));
            }
        });
        return notices;
    }

    /** How many days ahead a friend this close is offered to organize; a plain friend never is. */
    static int leadDays(Closeness closeness) {
        return switch (closeness) {
            case INTIMATE -> INTIMATE_LEAD_DAYS;
            case CLOSE -> CLOSE_LEAD_DAYS;
            case FRIEND -> -1;
        };
    }
}
