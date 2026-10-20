package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.ReunionSnoozes;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReunionServiceTest {

    /** Sunday 18 October 2026, 10:00 in Paris. */
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private static final Instant LONG_AGO = Instant.parse("2025-01-01T10:00:00Z");
    private static final String ME = "me";

    @Mock
    private EventRepository eventRepository;
    @Mock
    private FriendshipDirectory friendships;
    @Mock
    private ReunionSnoozes snoozes;

    private ReunionService service;

    @BeforeEach
    void setUp() {
        service = new ReunionService(eventRepository, friendships, snoozes, new DisplayNames(eventRepository),
                Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("Europe/Paris"));
        lenient().when(snoozes.snoozedBy(ME, NOW)).thenReturn(Mono.just(Set.of()));
        lenient().when(eventRepository.findBirthdaysOf(any())).thenReturn(Flux.empty());
        lenient().when(eventRepository.hasUpcomingShared(eq(ME), anyString(), eq(NOW))).thenReturn(Mono.just(false));
        lenient().when(eventRepository.findLastSharedOuting(eq(ME), anyString(), eq(NOW))).thenReturn(Mono.empty());
    }

    private static Friendship friend(String friendId, Closeness closeness, Instant since) {
        return new Friendship(ME, friendId, true, closeness, since, 1);
    }

    private static Event outing(String id, Instant start) {
        return Event.builder().id(id).title("Outing " + id).startDate(start).build();
    }

    private void myFriends(Friendship... pairs) {
        when(friendships.friendshipsOf(ME)).thenReturn(Flux.just(pairs));
    }

    @Test
    void candidatesFor_shouldKeepCloseFriendsOfNinetyDays_notSeenForNinetyDays_withNothingPlanned() {
        myFriends(
                friend("hugo", Closeness.CLOSE, LONG_AGO),
                friend("plain", Closeness.FRIEND, LONG_AGO),
                friend("recent-friend", Closeness.INTIMATE, NOW.minus(Duration.ofDays(89))),
                friend("seen-lately", Closeness.CLOSE, LONG_AGO),
                friend("planned", Closeness.INTIMATE, LONG_AGO));
        Event lastWithHugo = outing("e1", Instant.parse("2026-05-02T18:00:00Z"));
        when(eventRepository.findLastSharedOuting(ME, "hugo", NOW)).thenReturn(Mono.just(lastWithHugo));
        when(eventRepository.findLastSharedOuting(ME, "seen-lately", NOW))
                .thenReturn(Mono.just(outing("e2", NOW.minus(Duration.ofDays(89)))));
        when(eventRepository.hasUpcomingShared(ME, "planned", NOW)).thenReturn(Mono.just(true));
        when(eventRepository.findBirthdaysOf(List.of("hugo")))
                .thenReturn(Flux.just(BirthdayService.birthdayEvent("hugo", "Hugo", MonthDay.of(1, 5))));

        StepVerifier.create(service.candidatesFor(ME))
                .expectNext(new ReunionCandidate("hugo", "Hugo", Closeness.CLOSE, LONG_AGO, lastWithHugo.getStartDate(),
                        "e1", "Outing e1", 5))
                .verifyComplete();
        verify(eventRepository, never()).findLastSharedOuting(ME, "planned", NOW);
    }

    @Test
    void candidatesFor_shouldPutIntimatesFirst_thenTheLongestUnseen_neverSeenFirst_andNameUnknownsUnAmi() {
        myFriends(
                friend("close-old", Closeness.CLOSE, LONG_AGO),
                friend("intimate-recent", Closeness.INTIMATE, LONG_AGO),
                friend("intimate-never", Closeness.INTIMATE, LONG_AGO),
                friend("intimate-older", Closeness.INTIMATE, LONG_AGO));
        when(eventRepository.findLastSharedOuting(ME, "close-old", NOW))
                .thenReturn(Mono.just(outing("a", Instant.parse("2025-02-01T10:00:00Z"))));
        when(eventRepository.findLastSharedOuting(ME, "intimate-recent", NOW))
                .thenReturn(Mono.just(outing("b", Instant.parse("2026-06-01T10:00:00Z"))));
        when(eventRepository.findLastSharedOuting(ME, "intimate-older", NOW))
                .thenReturn(Mono.just(outing("c", Instant.parse("2026-03-01T10:00:00Z"))));

        StepVerifier.create(service.candidatesFor(ME))
                .assertNext(candidate -> {
                    assertThat(candidate.friendId()).isEqualTo("intimate-never");
                    assertThat(candidate.name()).isEqualTo(DisplayNames.FALLBACK);
                    assertThat(candidate.lastSharedAt()).isNull();
                    assertThat(candidate.monthsApart()).isNull();
                })
                .assertNext(candidate -> assertThat(candidate.friendId()).isEqualTo("intimate-older"))
                .assertNext(candidate -> assertThat(candidate.friendId()).isEqualTo("intimate-recent"))
                .assertNext(candidate -> assertThat(candidate.friendId()).isEqualTo("close-old"))
                .verifyComplete();
    }

    @Test
    void candidatesFor_shouldLeaveOutSnoozedFriends() {
        when(snoozes.snoozedBy(ME, NOW)).thenReturn(Mono.just(Set.of("hugo")));
        myFriends(friend("hugo", Closeness.CLOSE, LONG_AGO), friend("lea", Closeness.CLOSE, LONG_AGO));

        StepVerifier.create(service.candidatesFor(ME).map(ReunionCandidate::friendId)).expectNext("lea").verifyComplete();
        verify(eventRepository, never()).hasUpcomingShared(ME, "hugo", NOW);
    }

    @Test
    void candidatesFor_shouldFail_whenTheEventsCannotBeRead() {
        myFriends(friend("hugo", Closeness.CLOSE, LONG_AGO));
        when(eventRepository.hasUpcomingShared(ME, "hugo", NOW)).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.candidatesFor(ME)).expectError(IllegalStateException.class).verify();
    }

    @Test
    void reunionsFor_shouldKeepTheThreeMostDue() {
        myFriends(friend("a", Closeness.CLOSE, LONG_AGO), friend("b", Closeness.CLOSE, LONG_AGO),
                friend("c", Closeness.CLOSE, LONG_AGO), friend("d", Closeness.CLOSE, LONG_AGO));

        StepVerifier.create(service.reunionsFor(ME).map(ReunionCandidate::friendId))
                .expectNext("a", "b", "c")
                .verifyComplete();
    }

    @Test
    void dismiss_shouldSnoozeTheFriendForThirtyDays() {
        when(friendships.pairOf(ME, "hugo")).thenReturn(Mono.just(friend("hugo", Closeness.CLOSE, LONG_AGO)));
        when(snoozes.snooze(ME, "hugo", NOW.plus(Duration.ofDays(30)))).thenReturn(Mono.empty());

        StepVerifier.create(service.dismiss(ME, "hugo")).verifyComplete();
        verify(snoozes).snooze(ME, "hugo", NOW.plus(Duration.ofDays(30)));
    }

    @Test
    void dismiss_shouldAnswerNotFound_forAStrangerOrAFormerFriend() {
        when(friendships.pairOf(ME, "stranger")).thenReturn(Mono.empty());
        when(friendships.pairOf(ME, "former")).thenReturn(Mono.just(new Friendship(ME, "former", false, null, null, 3)));

        StepVerifier.create(service.dismiss(ME, "stranger"))
                .expectErrorMatches(error -> error instanceof BusinessException e
                        && e.getErrorCode() == BusinessErrorCode.FRIEND_NOT_FOUND)
                .verify();
        StepVerifier.create(service.dismiss(ME, "former"))
                .expectErrorMatches(error -> error instanceof BusinessException e
                        && e.getErrorCode() == BusinessErrorCode.FRIEND_NOT_FOUND)
                .verify();
        verify(snoozes, never()).snooze(anyString(), anyString(), any());
    }
}
