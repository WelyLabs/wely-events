package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.FriendshipNoticeType;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipReminderServiceTest {

    /** Sunday 18 October 2026, 08:00 in Paris. */
    private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Mock
    private FriendshipDirectory friendships;
    @Mock
    private ReunionService reunionService;
    @Mock
    private FriendshipNoticeOutbox noticeOutbox;
    @Mock
    private EventRepository eventRepository;

    private FriendshipReminderService service;

    @BeforeEach
    void setUp() {
        service = new FriendshipReminderService(friendships, reunionService, noticeOutbox, new DisplayNames(eventRepository),
                Clock.fixed(NOW, ZoneOffset.UTC), PARIS);
        lenient().when(noticeOutbox.record(any())).thenReturn(Mono.just(true));
        lenient().when(noticeOutbox.recordedFor(anyString(), eq(FriendshipNoticeType.REUNION_NUDGE), any()))
                .thenReturn(Flux.empty());
        lenient().when(reunionService.candidatesFor(anyString())).thenReturn(Flux.empty());
        lenient().when(eventRepository.findBirthdaysOf(any())).thenReturn(Flux.empty());
    }

    private static Friendship pair(String a, String b, Closeness closeness, String since) {
        return new Friendship(a, b, true, closeness, Instant.parse(since), 1);
    }

    private static ReunionCandidate candidate(String friendId) {
        return new ReunionCandidate(friendId, "Hugo", Closeness.CLOSE, Instant.parse("2025-01-01T10:00:00Z"),
                Instant.parse("2026-05-02T18:00:00Z"), "e1", "Ciné", 5);
    }

    private List<FriendshipNotice> recorded(int count) {
        ArgumentCaptor<FriendshipNotice> notices = ArgumentCaptor.forClass(FriendshipNotice.class);
        verify(noticeOutbox, times(count)).record(notices.capture());
        return notices.getAllValues();
    }

    // --- Anniversaries ---------------------------------------------------------

    @Test
    void recordDueNotices_shouldWishBothFriendsTheirAnniversary_onTheDayInParis() {
        // 17 October 2025 at 22:30 UTC is already the 18th in Paris.
        when(friendships.allFriendships()).thenReturn(Flux.just(pair("hugo", "lea", Closeness.FRIEND, "2025-10-17T22:30:00Z")));
        when(eventRepository.findBirthdaysOf(List.of("hugo", "lea")))
                .thenReturn(Flux.just(BirthdayService.birthdayEvent("lea", "Léa", MonthDay.of(6, 1))));

        StepVerifier.create(service.recordDueNotices()).expectNext(2L).verifyComplete();
        Instant since = Instant.parse("2025-10-17T22:30:00Z");
        assertThat(recorded(2)).containsExactly(
                FriendshipNotice.anniversary("hugo", "lea", "Léa", Closeness.FRIEND, 1, since, TODAY),
                FriendshipNotice.anniversary("lea", "hugo", DisplayNames.FALLBACK, Closeness.FRIEND, 1, since, TODAY));
    }

    @Test
    void recordDueNotices_shouldCountTheYears_andSkipAnyOtherDayOrAFirstYear() {
        when(friendships.allFriendships()).thenReturn(Flux.just(
                pair("a", "b", Closeness.FRIEND, "2023-10-18T10:00:00Z"),
                pair("c", "d", Closeness.FRIEND, "2025-10-19T10:00:00Z"),
                pair("e", "f", Closeness.FRIEND, "2026-10-18T05:00:00Z")));

        StepVerifier.create(service.recordDueNotices()).expectNext(2L).verifyComplete();
        assertThat(recorded(2)).allSatisfy(notice -> assertThat(notice.years()).isEqualTo(3));
    }

    @Test
    void yearsOnAnniversary_shouldMoveTheTwentyNinthOfFebruaryToTheTwentyEighth() {
        Friendship leap = pair("a", "b", Closeness.FRIEND, "2024-02-29T12:00:00Z");

        assertThat(service.yearsOnAnniversary(leap, LocalDate.of(2025, 2, 28))).contains(1);
        assertThat(service.yearsOnAnniversary(leap, LocalDate.of(2025, 3, 1))).isEmpty();
        assertThat(service.yearsOnAnniversary(leap, LocalDate.of(2028, 2, 29))).contains(4);
        assertThat(service.yearsOnAnniversary(new Friendship("a", "b", true, Closeness.FRIEND, null, 1), TODAY)).isEmpty();
    }

    @Test
    void recordDueNotices_shouldCountOnlyNewlyRecordedNotices() {
        when(friendships.allFriendships()).thenReturn(Flux.just(pair("a", "b", Closeness.FRIEND, "2025-10-18T10:00:00Z")));
        when(noticeOutbox.record(any())).thenReturn(Mono.just(false));

        StepVerifier.create(service.recordDueNotices()).expectNext(0L).verifyComplete();
    }

    @Test
    void recordDueNotices_shouldGoOn_whenOneAnniversaryFails() {
        when(friendships.allFriendships()).thenReturn(Flux.just(
                pair("a", "b", Closeness.FRIEND, "2025-10-18T10:00:00Z"),
                pair("c", "d", Closeness.FRIEND, "2025-10-18T10:00:00Z")));
        when(eventRepository.findBirthdaysOf(List.of("a", "b"))).thenReturn(Flux.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.recordDueNotices()).expectNext(2L).verifyComplete();
    }

    // --- Reunion nudges --------------------------------------------------------

    @Test
    void recordDueNotices_shouldNudgeEachUserWithACloseFriend_aboutTheirMostDueCandidate() {
        when(friendships.allFriendships()).thenReturn(Flux.just(
                pair("hugo", "me", Closeness.CLOSE, "2025-01-01T10:00:00Z"),
                pair("me", "zoe", Closeness.FRIEND, "2025-01-01T10:00:00Z")));
        when(reunionService.candidatesFor("me")).thenReturn(Flux.just(candidate("hugo"), candidate("other")));

        StepVerifier.create(service.recordDueNotices()).expectNext(1L).verifyComplete();
        assertThat(recorded(1)).containsExactly(FriendshipNotice.reunionNudge("me", candidate("hugo"), TODAY));
        verify(reunionService, never()).candidatesFor("zoe");
        verify(noticeOutbox).recordedFor("me", FriendshipNoticeType.REUNION_NUDGE, NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void recordDueNotices_shouldNudgeNobodyTwiceInAWeek() {
        when(friendships.allFriendships()).thenReturn(Flux.just(pair("hugo", "me", Closeness.CLOSE, "2025-01-01T10:00:00Z")));
        FriendshipNotice sixDaysAgo = FriendshipNotice.reunionNudge("me", candidate("lea"), TODAY.minusDays(6));
        when(noticeOutbox.recordedFor("me", FriendshipNoticeType.REUNION_NUDGE, NOW.minus(Duration.ofDays(30))))
                .thenReturn(Flux.just(sixDaysAgo));

        StepVerifier.create(service.recordDueNotices()).expectNext(0L).verifyComplete();
        verify(reunionService, never()).candidatesFor("me");
    }

    @Test
    void recordDueNotices_shouldSkipAFriendNudgedAboutThisMonth_andNudgeAboutTheNext() {
        when(friendships.allFriendships()).thenReturn(Flux.just(pair("hugo", "me", Closeness.CLOSE, "2025-01-01T10:00:00Z")));
        FriendshipNotice weekAgo = FriendshipNotice.reunionNudge("me", candidate("hugo"), TODAY.minusDays(7));
        when(noticeOutbox.recordedFor("me", FriendshipNoticeType.REUNION_NUDGE, NOW.minus(Duration.ofDays(30))))
                .thenReturn(Flux.just(weekAgo));
        when(reunionService.candidatesFor("me")).thenReturn(Flux.just(candidate("hugo"), candidate("lea")));

        StepVerifier.create(service.recordDueNotices()).expectNext(1L).verifyComplete();
        assertThat(recorded(1)).extracting(FriendshipNotice::friendId).containsExactly("lea");
    }

    @Test
    void recordDueNotices_shouldGoOn_whenOneUsersNudgeFails() {
        when(friendships.allFriendships()).thenReturn(Flux.just(pair("a", "b", Closeness.INTIMATE, "2025-01-01T10:00:00Z")));
        when(reunionService.candidatesFor("a")).thenReturn(Flux.error(new IllegalStateException("mongo down")));
        when(reunionService.candidatesFor("b")).thenReturn(Flux.just(candidate("a")));

        StepVerifier.create(service.recordDueNotices()).expectNext(1L).verifyComplete();
    }

    @Test
    void recordDueNotices_shouldFail_whenTheFriendshipsCannotBeRead() {
        when(friendships.allFriendships()).thenReturn(Flux.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.recordDueNotices()).expectError(IllegalStateException.class).verify();
    }
}
