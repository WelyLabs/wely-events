package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.FriendshipNoticeType;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The daily friendship notices, run after the birthday reminders. Every notice is recorded under a key
 * that makes a second run, a second pod or a catch-up run harmless:
 * <ul>
 *   <li>FRIENDSHIP_ANNIVERSARY to both friends on the day they became friends, from the first year on;</li>
 *   <li>REUNION_NUDGE about one {@link ReunionService} candidate, at most once a week per user, at most once
 *       a month about the same friend, and never while the user snoozes that friend.</li>
 * </ul>
 * Friends are read from the {@link FriendshipDirectory}: the job runs on nobody's behalf.
 */
public class FriendshipReminderService {

    static final Duration NUDGE_EVERY = Duration.ofDays(7);
    static final Duration SAME_FRIEND_EVERY = Duration.ofDays(30);

    private static final System.Logger LOG = System.getLogger(FriendshipReminderService.class.getName());

    private final FriendshipDirectory friendships;
    private final ReunionService reunionService;
    private final FriendshipNoticeOutbox noticeOutbox;
    private final DisplayNames displayNames;
    private final Clock clock;
    private final ZoneId zone;

    public FriendshipReminderService(FriendshipDirectory friendships, ReunionService reunionService,
                                     FriendshipNoticeOutbox noticeOutbox, DisplayNames displayNames, Clock clock,
                                     ZoneId zone) {
        this.friendships = friendships;
        this.reunionService = reunionService;
        this.noticeOutbox = noticeOutbox;
        this.displayNames = displayNames;
        this.clock = clock;
        this.zone = zone;
    }

    /** One run; returns how many notices were newly recorded. One pair or user failing never stops the others. */
    public Mono<Long> recordDueNotices() {
        LocalDate today = clock.instant().atZone(zone).toLocalDate();
        return Flux.defer(friendships::allFriendships)
                .collectList()
                .flatMapMany(pairs -> Flux.concat(anniversaries(pairs, today), nudges(pairs, today)))
                .count();
    }

    // --- Anniversaries --------------------------------------------------------

    private Flux<Boolean> anniversaries(List<Friendship> pairs, LocalDate today) {
        return Flux.fromIterable(pairs)
                .filter(pair -> yearsOnAnniversary(pair, today).isPresent())
                .concatMap(pair -> anniversaryNotices(pair, today)
                        .concatMap(noticeOutbox::record)
                        .filter(Boolean::booleanValue)
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Anniversary of {0} not recorded: {1}",
                                    pair.pairId(), error.toString());
                            return Flux.empty();
                        }));
    }

    /** The years of friendship when today is its anniversary, from one; a 29 February falls on the 28th. */
    Optional<Integer> yearsOnAnniversary(Friendship pair, LocalDate today) {
        if (!pair.friends() || pair.since() == null) {
            return Optional.empty();
        }
        LocalDate start = pair.since().atZone(zone).toLocalDate();
        int years = today.getYear() - start.getYear();
        return years >= 1 && start.plusYears(years).equals(today) ? Optional.of(years) : Optional.empty();
    }

    private Flux<FriendshipNotice> anniversaryNotices(Friendship pair, LocalDate today) {
        int years = yearsOnAnniversary(pair, today).orElseThrow();
        return displayNames.of(List.of(pair.userA(), pair.userB()))
                .flatMapIterable(names -> List.of(
                        FriendshipNotice.anniversary(pair.userA(), pair.userB(), DisplayNames.nameIn(names, pair.userB()),
                                pair.closeness(), years, pair.since(), today),
                        FriendshipNotice.anniversary(pair.userB(), pair.userA(), DisplayNames.nameIn(names, pair.userA()),
                                pair.closeness(), years, pair.since(), today)));
    }

    // --- Reunion nudges -------------------------------------------------------

    /** Everyone with a close or intimate friend, each on their own. */
    private Flux<Boolean> nudges(List<Friendship> pairs, LocalDate today) {
        Set<String> users = pairs.stream()
                .filter(pair -> pair.friends() && pair.closeness().compareTo(Closeness.CLOSE) >= 0)
                .flatMap(pair -> Stream.of(pair.userA(), pair.userB()))
                .collect(Collectors.toCollection(TreeSet::new));
        return Flux.fromIterable(users)
                .concatMap(userId -> nudge(userId, today)
                        .onErrorResume(error -> {
                            LOG.log(System.Logger.Level.WARNING, "Reunion nudge of {0} not recorded: {1}",
                                    userId, error.toString());
                            return Mono.empty();
                        }));
    }

    /** At most one: none if nudged this week, else the most due candidate not nudged about this month. */
    private Mono<Boolean> nudge(String userId, LocalDate today) {
        Instant now = clock.instant();
        return noticeOutbox.recordedFor(userId, FriendshipNoticeType.REUNION_NUDGE, now.minus(SAME_FRIEND_EVERY))
                .collectList()
                .flatMap(recent -> {
                    boolean nudgedThisWeek = recent.stream()
                            .anyMatch(notice -> !notice.day().isBefore(today.minusDays(NUDGE_EVERY.toDays() - 1)));
                    if (nudgedThisWeek) {
                        return Mono.empty();
                    }
                    Set<String> recentFriends = recent.stream().map(FriendshipNotice::friendId).collect(Collectors.toSet());
                    return reunionService.candidatesFor(userId)
                            .filter(candidate -> !recentFriends.contains(candidate.friendId()))
                            .next()
                            .flatMap(candidate -> record(userId, candidate, today));
                });
    }

    private Mono<Boolean> record(String userId, ReunionCandidate candidate, LocalDate today) {
        return noticeOutbox.record(FriendshipNotice.reunionNudge(userId, candidate, today)).filter(Boolean::booleanValue);
    }
}
