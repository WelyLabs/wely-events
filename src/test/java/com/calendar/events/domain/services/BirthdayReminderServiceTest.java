package com.calendar.events.domain.services;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
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
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BirthdayReminderServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalDate TODAY = LocalDate.parse("2027-02-19");

    @Mock
    private EventRepository eventRepository;
    @Mock
    private FriendshipDirectory friendships;
    @Mock
    private BirthdayNoticeOutbox noticeOutbox;
    @Mock
    private DeletedUserMarkers markers;

    private BirthdayReminderService service;

    @BeforeEach
    void setUp() {
        // 23:30 UTC on the 18th is already the 19th in Paris.
        Clock clock = Clock.fixed(Instant.parse("2027-02-18T23:30:00Z"), ZoneOffset.UTC);
        service = new BirthdayReminderService(eventRepository, friendships, noticeOutbox, new AccountGuard(markers),
                clock, PARIS);
        lenient().when(markers.deletedAmong(any())).thenReturn(Mono.just(Set.of()));
        lenient().when(eventRepository.findSurpriseByOccurrence(anyString())).thenReturn(Mono.empty());
    }

    private static Event birthdayOf(String userId, String name, LocalDate on) {
        return BirthdayService.birthdayEvent(userId, name, MonthDay.from(on));
    }

    private static final Flux<Friend> LEAS_FRIENDS = Flux.just(new Friend("zoe", Closeness.INTIMATE),
            new Friend("bob", Closeness.CLOSE), new Friend("hugo", Closeness.FRIEND));

    private List<BirthdayNotice> noticesFor(Event birthday, LocalDate today) {
        return service.noticesFor(birthday, today).collectList().block();
    }

    @Test
    void noticesFor_shouldProposeASurpriseToIntimateFriendsFrom21DaysAhead() {
        when(friendships.friendsOf("lea")).thenReturn(LEAS_FRIENDS);

        assertThat(noticesFor(birthdayOf("lea", "Léa", TODAY.plusDays(21)), TODAY))
                .containsExactly(new BirthdayNotice(BirthdayNoticeType.SURPRISE_PROPOSAL, "zoe", "lea", "Léa",
                        TODAY.plusDays(21), 21, Closeness.INTIMATE, null, null, null));
    }

    @Test
    void noticesFor_shouldProposeToCloseFriendsFrom14DaysAhead_namingAnExistingSurprise() {
        when(friendships.friendsOf("lea")).thenReturn(LEAS_FRIENDS);
        Event surprise = Event.builder().id("s1").organizerId("max").organizerName("max_r")
                .participantIds(new ArrayList<>(List.of("max", "zoe"))).build();
        when(eventRepository.findSurpriseByOccurrence("lea:2027")).thenReturn(Mono.just(surprise));

        // Zoé already takes part: nothing to propose to her.
        assertThat(noticesFor(birthdayOf("lea", "Léa", TODAY.plusDays(14)), TODAY))
                .containsExactly(new BirthdayNotice(BirthdayNoticeType.SURPRISE_PROPOSAL, "bob", "lea", "Léa",
                        TODAY.plusDays(14), 14, Closeness.CLOSE, "s1", "max", "max_r"));
    }

    @Test
    void noticesFor_shouldWishEveryFriendAndTheHonoree_onTheDay() {
        when(friendships.friendsOf("lea")).thenReturn(LEAS_FRIENDS);

        assertThat(noticesFor(birthdayOf("lea", "Léa", TODAY), TODAY))
                .extracting(BirthdayNotice::type, BirthdayNotice::recipientId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(BirthdayNoticeType.BIRTHDAY_TODAY, "lea"),
                        org.assertj.core.groups.Tuple.tuple(BirthdayNoticeType.FRIEND_BIRTHDAY, "zoe"),
                        org.assertj.core.groups.Tuple.tuple(BirthdayNoticeType.FRIEND_BIRTHDAY, "bob"),
                        org.assertj.core.groups.Tuple.tuple(BirthdayNoticeType.FRIEND_BIRTHDAY, "hugo"));
    }

    @Test
    void noticesFor_shouldSendNothingMoreThan21DaysAhead() {
        assertThat(noticesFor(birthdayOf("lea", "Léa", TODAY.plusDays(22)), TODAY)).isEmpty();
        verify(friendships, never()).friendsOf(anyString());
    }

    @Test
    void noticesFor_shouldLookAtNextYear_onceThisYearsBirthdayHasPassed() {
        when(friendships.friendsOf("lea")).thenReturn(LEAS_FRIENDS);
        LocalDate lastDecember = LocalDate.parse("2026-12-20");

        assertThat(noticesFor(birthdayOf("lea", "Léa", LocalDate.parse("2027-01-05")), lastDecember))
                .singleElement()
                .satisfies(notice -> {
                    assertThat(notice.date()).isEqualTo(LocalDate.parse("2027-01-05"));
                    assertThat(notice.daysUntil()).isEqualTo(16);
                });
    }

    @Test
    void noticesFor_shouldSkipDeletedFriends_andTheHonoreeAmongTheirFriends() {
        when(friendships.friendsOf("lea")).thenReturn(Flux.just(new Friend("zoe", Closeness.INTIMATE), new Friend("lea", Closeness.CLOSE)));
        when(markers.deletedAmong(any())).thenReturn(Mono.just(Set.of("zoe")));

        assertThat(noticesFor(birthdayOf("lea", "Léa", TODAY.plusDays(3)), TODAY)).isEmpty();
    }

    @Test
    void noticesFor_shouldIgnoreAnEventWithoutABirthDate() {
        assertThat(noticesFor(Event.builder().organizerId("lea").build(), TODAY)).isEmpty();
    }

    @Test
    void recordDueNotices_shouldRecordWhatIsDueInParis_andCountOnlyTheNewOnes() {
        when(eventRepository.findAllBirthdays()).thenReturn(Flux.just(birthdayOf("lea", "Léa", TODAY)));
        when(friendships.friendsOf("lea")).thenReturn(Flux.just(new Friend("zoe", Closeness.INTIMATE)));
        when(noticeOutbox.record(any())).thenAnswer(call ->
                Mono.just(((BirthdayNotice) call.getArgument(0)).type() == BirthdayNoticeType.BIRTHDAY_TODAY));

        StepVerifier.create(service.recordDueNotices()).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void recordDueNotices_shouldGoOnWithTheNextBirthday_whenOneFails() {
        when(eventRepository.findAllBirthdays()).thenReturn(Flux.just(
                birthdayOf("lea", "Léa", TODAY), birthdayOf("max", "Max", TODAY)));
        when(friendships.friendsOf("lea")).thenReturn(Flux.error(new IllegalStateException("mongo down")));
        when(friendships.friendsOf("max")).thenReturn(Flux.empty());
        when(noticeOutbox.record(any())).thenReturn(Mono.just(true));

        StepVerifier.create(service.recordDueNotices()).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void leadDays_shouldBe21ForIntimate14ForClose_andNeverForAPlainFriend() {
        assertThat(BirthdayReminderService.leadDays(Closeness.INTIMATE)).isEqualTo(21);
        assertThat(BirthdayReminderService.leadDays(Closeness.CLOSE)).isEqualTo(14);
        assertThat(BirthdayReminderService.leadDays(Closeness.FRIEND)).isNegative();
    }

    @Test
    void nextOccurrence_shouldBeTodayOnTheDay_andFallOnThe28thForA29February() {
        assertThat(BirthdayReminderService.nextOccurrence(MonthDay.of(2, 19), TODAY)).isEqualTo(TODAY);
        assertThat(BirthdayReminderService.nextOccurrence(MonthDay.of(2, 29), TODAY)).isEqualTo(LocalDate.parse("2027-02-28"));
    }
}
