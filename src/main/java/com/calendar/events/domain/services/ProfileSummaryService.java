package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.domain.models.Ledger;
import com.calendar.events.domain.models.ProfileSummary;
import com.calendar.events.domain.models.ProfileSummary.Companion;
import com.calendar.events.domain.models.ProfileSummary.EventBalance;
import com.calendar.events.domain.models.ProfileSummary.NewFriend;
import com.calendar.events.domain.models.ProfileSummary.Place;
import com.calendar.events.domain.models.ProfileSummary.Upgrade;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.FriendshipHistory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a member's profile summary from the events they are part of and those events' expenses.
 *
 * <p>Only the member's own events are read, and only through them or their own friendships is anyone
 * else named: the circle lists people the member already sees on those events' pages, the year in
 * review the member's own friends.
 */
public class ProfileSummaryService {

    public static final int UPCOMING_SIZE = 3;
    public static final int MEMORIES_SIZE = 6;
    public static final int CIRCLE_SIZE = 5;
    public static final int PLACES_SIZE = 20;
    public static final int TOP_FRIENDS_SIZE = 3;

    private final EventRepository eventRepository;
    private final ExpenseRepository expenseRepository;
    private final FriendshipDirectory friendships;
    private final FriendshipHistory friendshipHistory;
    private final Clock clock;
    private final ZoneId zone;

    public ProfileSummaryService(EventRepository eventRepository, ExpenseRepository expenseRepository,
                                 FriendshipDirectory friendships, FriendshipHistory friendshipHistory,
                                 Clock clock, ZoneId zone) {
        this.eventRepository = eventRepository;
        this.expenseRepository = expenseRepository;
        this.friendships = friendships;
        this.friendshipHistory = friendshipHistory;
        this.clock = clock;
        this.zone = zone;
    }

    public Mono<ProfileSummary> summaryOf(String userId) {
        Instant yearStart = yearStart(clock.instant());
        Mono<List<Friendship>> currentFriends = friendships.friendshipsOf(userId).collectList();
        Mono<List<FriendshipHistoryEntry>> thisYearsChanges = friendshipHistory.involvingSince(userId, yearStart).collectList();
        return eventRepository.findInvolving(userId).collectList()
                .flatMap(events -> Mono.zip(balancesOf(userId, events), currentFriends, thisYearsChanges)
                        .map(found -> summarize(userId, events, found.getT1(), found.getT2(), found.getT3())));
    }

    private Instant yearStart(Instant now) {
        return now.atZone(zone).withDayOfYear(1).toLocalDate().atStartOfDay(zone).toInstant();
    }

    private ProfileSummary summarize(String userId, List<Event> events, List<EventBalance> balances,
                                     List<Friendship> currentFriends, List<FriendshipHistoryEntry> thisYearsChanges) {
        Instant now = clock.instant();
        ZonedDateTime today = now.atZone(zone);
        Instant yearStart = yearStart(now);
        Instant monthStart = today.withDayOfMonth(1).toLocalDate().atStartOfDay(zone).toInstant();

        List<Event> started = events.stream().filter(event -> !event.getStartDate().isAfter(now)).toList();
        List<Event> thisYear = started.stream().filter(event -> !event.getStartDate().isBefore(yearStart)).toList();
        List<Event> past = started.stream().filter(event -> event.effectiveEnd().isBefore(now))
                .sorted(Comparator.comparing(Event::getStartDate).reversed())
                .toList();

        return new ProfileSummary(
                thisYear.size(),
                (int) thisYear.stream().filter(event -> !event.getStartDate().isBefore(monthStart)).count(),
                (int) thisYear.stream().filter(event -> userId.equals(event.getOrganizerId())).count(),
                (int) thisYear.stream().map(ProfileSummaryService::placeKey).distinct().count(),
                events.stream().filter(event -> event.getStartDate().isAfter(now))
                        .sorted(Comparator.comparing(Event::getStartDate))
                        .limit(UPCOMING_SIZE)
                        .toList(),
                past.stream().limit(MEMORIES_SIZE).toList(),
                past.size(),
                circleOf(userId, past),
                placesOf(past),
                balances,
                newFriendsOf(currentFriends, userId, yearStart),
                upgradesOf(thisYearsChanges, userId, friendIdsOf(currentFriends, userId)),
                topFriendsOf(userId, thisYear, friendIdsOf(currentFriends, userId)));
    }

    private static Set<String> friendIdsOf(List<Friendship> currentFriends, String userId) {
        return currentFriends.stream().map(friendship -> friendship.otherThan(userId)).collect(Collectors.toSet());
    }

    /** Friends made since the year began and still friends, the most recent first. */
    private static List<NewFriend> newFriendsOf(List<Friendship> currentFriends, String userId, Instant yearStart) {
        return currentFriends.stream()
                .filter(friendship -> friendship.since() != null && !friendship.since().isBefore(yearStart))
                .map(friendship -> new NewFriend(friendship.otherThan(userId), friendship.since()))
                .sorted(Comparator.comparing(NewFriend::since).reversed().thenComparing(NewFriend::userId))
                .toList();
    }

    /** Each current friend's latest move closer this year, oldest first, as the story of the year reads. */
    private static List<Upgrade> upgradesOf(List<FriendshipHistoryEntry> changes, String userId, Set<String> friendIds) {
        Map<String, Upgrade> latest = new LinkedHashMap<>();
        changes.stream()
                .filter(FriendshipHistoryEntry::isUpgrade)
                .filter(change -> friendIds.contains(change.otherThan(userId)))
                .sorted(Comparator.comparing(FriendshipHistoryEntry::at))
                .forEach(change -> latest.put(change.otherThan(userId),
                        new Upgrade(change.otherThan(userId), change.closeness(), change.at())));
        return latest.values().stream().sorted(Comparator.comparing(Upgrade::at)).toList();
    }

    /** The current friends on most of this year's outings, most frequent first; ties in id order. */
    private static List<Companion> topFriendsOf(String userId, List<Event> thisYear, Set<String> friendIds) {
        Map<String, Integer> counts = new HashMap<>();
        for (Event event : thisYear) {
            friendIds.stream()
                    .filter(friendId -> !friendId.equals(userId) && event.involves(friendId))
                    .forEach(friendId -> counts.merge(friendId, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_FRIENDS_SIZE)
                .map(entry -> new Companion(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Everyone else on the member's past outings, most frequent first; ties in id order. */
    private static List<Companion> circleOf(String userId, List<Event> past) {
        Map<String, Integer> counts = new HashMap<>();
        for (Event event : past) {
            event.getParticipantIds().stream()
                    .filter(id -> !id.equals(userId))
                    .forEach(id -> counts.merge(id, 1, Integer::sum));
            if (event.getOrganizerId() != null && !event.getOrganizerId().equals(userId)
                    && !event.getParticipantIds().contains(event.getOrganizerId())) {
                counts.merge(event.getOrganizerId(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(CIRCLE_SIZE)
                .map(entry -> new Companion(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * Past outings grouped by place, most visited first. A visit picked on the map names the place
     * and gives its coordinates; without one, the latest spelling does, spaces tidied.
     */
    private static List<Place> placesOf(List<Event> past) {
        Map<String, List<Event>> byPlace = past.stream()
                .collect(Collectors.groupingBy(ProfileSummaryService::placeKey, LinkedHashMap::new, Collectors.toList()));
        return byPlace.values().stream()
                .map(visits -> {
                    Event named = visits.stream().filter(visit -> visit.getCoordinates() != null).findFirst()
                            .orElse(visits.getFirst());
                    return new Place(tidy(named.getLocation()), named.getCoordinates(), visits.size());
                })
                .sorted(Comparator.comparingInt(Place::visits).reversed().thenComparing(Place::label))
                .limit(PLACES_SIZE)
                .toList();
    }

    /** « Chez Léa » and « chez léa  » are one place. */
    private static String placeKey(Event event) {
        return tidy(event.getLocation()).toLowerCase(Locale.ROOT);
    }

    private static String tidy(String location) {
        return location == null ? "" : location.strip().replaceAll("\\s+", " ");
    }

    /** The member's balance on each of their events that has expenses and where they are not even. */
    private Mono<List<EventBalance>> balancesOf(String userId, List<Event> events) {
        if (events.isEmpty()) {
            return Mono.just(List.of());
        }
        Map<String, Event> byId = events.stream().collect(Collectors.toMap(Event::getId, Function.identity()));
        return expenseRepository.findByEvents(byId.keySet()).collectList()
                .map(expenses -> expenses.stream()
                        .collect(Collectors.groupingBy(Expense::eventId))
                        .entrySet().stream()
                        .map(entry -> new EventBalance(entry.getKey(), byId.get(entry.getKey()).getTitle(),
                                Ledger.of(entry.getValue()).balances().getOrDefault(userId, 0L)))
                        .filter(balance -> balance.netCents() != 0)
                        .sorted(Comparator.comparingLong((EventBalance balance) -> Math.abs(balance.netCents())).reversed()
                                .thenComparing(EventBalance::eventId))
                        .toList());
    }
}
