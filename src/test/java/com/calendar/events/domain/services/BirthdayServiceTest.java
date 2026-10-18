package com.calendar.events.domain.services;

import com.calendar.events.domain.models.BirthdayOccurrence;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.CommunityDirectory;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BirthdayServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private static final Instant FROM = Instant.parse("2027-03-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-04-01T00:00:00Z");

    @Mock
    private EventRepository eventRepository;
    @Mock
    private SocialGraph socialGraph;
    @Mock
    private BirthdayNoticeOutbox noticeOutbox;
    @Mock
    private DeletedUserMarkers markers;
    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private EventDeletionOutbox deletionOutbox;

    private BirthdayService service;

    @BeforeEach
    void setUp() {
        AccountGuard guard = new AccountGuard(markers);
        EventService eventService = new EventService(eventRepository, socialGraph, expenseRepository,
                new PictureService(org.mockito.Mockito.mock(MediaStore.class)), deletionOutbox,
                org.mockito.Mockito.mock(CommunityDirectory.class), guard, Clock.fixed(NOW, ZoneOffset.UTC));
        service = new BirthdayService(eventRepository, eventService, socialGraph, noticeOutbox, guard);
        lenient().when(deletionOutbox.record(any())).thenReturn(Mono.empty());
    }

    private static Event birthdayOf(String userId, String name, int month, int day) {
        return BirthdayService.birthdayEvent(userId, name, MonthDay.of(month, day));
    }

    private static boolean hasCode(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    // --- recordBirthday ----------------------------------------------------------------------------

    @Test
    void recordBirthday_shouldUpsertTheUsersOneAllDayBirthday() {
        when(markers.isDeleted("lea")).thenReturn(Mono.just(false));
        when(eventRepository.upsertBirthday(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(service.recordBirthday("lea", "Léa", MonthDay.of(3, 12)))
                .assertNext(birthday -> {
                    assertThat(birthday.getKind()).isEqualTo(EventKind.BIRTHDAY);
                    assertThat(birthday.getTitle()).isEqualTo("Anniversaire de Léa");
                    assertThat(birthday.getOrganizerId()).isEqualTo("lea");
                    assertThat(birthday.getHonoreeId()).isEqualTo("lea");
                    assertThat(birthday.getHonoreeName()).isEqualTo("Léa");
                    assertThat(birthday.birthMonthDay()).isEqualTo(MonthDay.of(3, 12));
                    assertThat(birthday.isAllDay()).isTrue();
                    assertThat(birthday.getStartDate()).isEqualTo(Instant.parse("2000-03-12T00:00:00Z"));
                    assertThat(birthday.getEndDate()).isEqualTo(Instant.parse("2000-03-13T00:00:00Z"));
                    assertThat(birthday.getVisibility()).isEqualTo(Visibility.FRIENDS);
                    assertThat(birthday.getParticipantIds()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void recordBirthday_shouldGiveA29FebruaryADate() {
        assertThat(birthdayOf("lea", "Léa", 2, 29).getStartDate()).isEqualTo(Instant.parse("2000-02-29T00:00:00Z"));
    }

    @Test
    void recordBirthday_shouldIgnoreADeletedAccount() {
        when(markers.isDeleted("lea")).thenReturn(Mono.just(true));

        StepVerifier.create(service.recordBirthday("lea", "Léa", MonthDay.of(3, 12))).verifyComplete();
        verify(eventRepository, never()).upsertBirthday(any());
    }

    // --- birthdaysFor ------------------------------------------------------------------------------

    @Test
    void birthdaysFor_shouldListMineAndMyFriends_withClosenessAndSurprises() {
        when(socialGraph.friendsOf("me"))
                .thenReturn(Mono.just(Map.of("lea", Closeness.CLOSE, "hugo", Closeness.FRIEND, "zoe", Closeness.INTIMATE)));
        when(eventRepository.findBirthdaysOf(Set.of("me", "lea", "hugo", "zoe"))).thenReturn(Flux.just(
                birthdayOf("me", "Moi", 3, 20), birthdayOf("lea", "Léa", 3, 12),
                birthdayOf("hugo", "Hugo", 3, 5), birthdayOf("zoe", "Zoé", 3, 28)));
        Event surprise = Event.builder().id("s1").organizerId("bob").kind(EventKind.SURPRISE)
                .occurrenceKey("zoe:2027").participantIds(new ArrayList<>(List.of("bob", "me"))).build();
        when(eventRepository.findSurprisesFor(anyCollection(), org.mockito.ArgumentMatchers.eq("me")))
                .thenReturn(Flux.just(surprise));

        StepVerifier.create(service.birthdaysFor("me", FROM, TO))
                .expectNext(new BirthdayOccurrence("hugo", "Hugo", LocalDate.parse("2027-03-05"), false,
                        Closeness.FRIEND, false, null))
                .expectNext(new BirthdayOccurrence("lea", "Léa", LocalDate.parse("2027-03-12"), false,
                        Closeness.CLOSE, true, null))
                .expectNext(new BirthdayOccurrence("me", "Moi", LocalDate.parse("2027-03-20"), true,
                        null, false, null))
                .expectNext(new BirthdayOccurrence("zoe", "Zoé", LocalDate.parse("2027-03-28"), false,
                        Closeness.INTIMATE, false, new BirthdayOccurrence.Surprise("s1", "bob", true)))
                .verifyComplete();
    }

    @Test
    void birthdaysFor_shouldNeverLookForASurpriseForTheViewer() {
        when(socialGraph.friendsOf("me")).thenReturn(Mono.just(Map.of()));
        when(eventRepository.findBirthdaysOf(Set.of("me"))).thenReturn(Flux.just(birthdayOf("me", "Moi", 3, 20)));

        StepVerifier.create(service.birthdaysFor("me", FROM, TO))
                .assertNext(mine -> assertThat(mine.surprise()).isNull())
                .verifyComplete();
        verify(eventRepository, never()).findSurprisesFor(anyCollection(), anyString());
    }

    @Test
    void birthdaysFor_shouldShowA29FebruaryOnThe28thInACommonYear() {
        when(socialGraph.friendsOf("me")).thenReturn(Mono.just(Map.of("lea", Closeness.FRIEND)));
        when(eventRepository.findBirthdaysOf(Set.of("me", "lea"))).thenReturn(Flux.just(birthdayOf("lea", "Léa", 2, 29)));
        when(eventRepository.findSurprisesFor(anyCollection(), anyString())).thenReturn(Flux.empty());

        StepVerifier.create(service.birthdaysFor("me", Instant.parse("2027-02-01T00:00:00Z"), Instant.parse("2027-03-01T00:00:00Z")))
                .assertNext(occurrence -> assertThat(occurrence.date()).isEqualTo(LocalDate.parse("2027-02-28")))
                .verifyComplete();
    }

    @Test
    void birthdaysFor_shouldListEveryYearsOccurrenceInARangeAcrossNewYear() {
        when(socialGraph.friendsOf("me")).thenReturn(Mono.just(Map.of()));
        when(eventRepository.findBirthdaysOf(Set.of("me"))).thenReturn(Flux.just(birthdayOf("me", "Moi", 1, 10)));

        StepVerifier.create(service.birthdaysFor("me", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-31T00:00:00Z")))
                .assertNext(first -> assertThat(first.date()).isEqualTo(LocalDate.parse("2026-01-10")))
                .assertNext(second -> assertThat(second.date()).isEqualTo(LocalDate.parse("2027-01-10")))
                .verifyComplete();
    }

    @Test
    void birthdaysFor_shouldFallBackToMyOwn_whenSocialIsDown() {
        when(socialGraph.friendsOf("me")).thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));
        when(eventRepository.findBirthdaysOf(Set.of("me"))).thenReturn(Flux.just(birthdayOf("me", "Moi", 3, 20)));

        StepVerifier.create(service.birthdaysFor("me", FROM, TO))
                .assertNext(mine -> assertThat(mine.mine()).isTrue())
                .verifyComplete();
    }

    @Test
    void birthdaysFor_shouldListMyOwn_whenSocialAnswersNothing() {
        when(socialGraph.friendsOf("me")).thenReturn(Mono.empty());
        when(eventRepository.findBirthdaysOf(Set.of("me"))).thenReturn(Flux.just(birthdayOf("me", "Moi", 3, 20)));

        StepVerifier.create(service.birthdaysFor("me", FROM, TO))
                .assertNext(mine -> assertThat(mine.mine()).isTrue())
                .verifyComplete();
    }

    @Test
    void birthdaysFor_shouldRefuseARangeBackwardsOrBeyond400Days() {
        StepVerifier.create(service.birthdaysFor("me", TO, FROM))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.INVALID_BIRTHDAY_RANGE)).verify();
        StepVerifier.create(service.birthdaysFor("me", FROM, FROM.plus(java.time.Duration.ofDays(401))))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.INVALID_BIRTHDAY_RANGE)).verify();
        StepVerifier.create(service.birthdaysFor("me", null, TO))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.INVALID_BIRTHDAY_RANGE)).verify();
        verify(socialGraph, never()).friendsOf(anyString());
    }

    @Test
    void datesBetween_shouldKeepADayWhoseUtcDayOverlapsTheRange() {
        // A range starting at noon still shows that day's birthday.
        assertThat(BirthdayService.datesBetween(MonthDay.of(3, 12), Instant.parse("2027-03-12T12:00:00Z"),
                Instant.parse("2027-03-13T00:00:00Z"))).containsExactly(LocalDate.parse("2027-03-12"));
        assertThat(BirthdayService.datesBetween(MonthDay.of(3, 12), Instant.parse("2027-03-13T00:00:00Z"),
                Instant.parse("2027-03-20T00:00:00Z"))).isEmpty();
        assertThat(BirthdayService.datesBetween(null, FROM, TO)).isEmpty();
    }

    // --- forget ------------------------------------------------------------------------------------

    @Test
    void forget_shouldDropTheBirthdayTheSurprisesForItAndThePendingNotices() {
        Event surprise = Event.builder().id("s1").organizerId("bob").title("Surprise").kind(EventKind.SURPRISE)
                .participantIds(new ArrayList<>(List.of("bob"))).build();
        when(eventRepository.deleteBirthdayOf("lea")).thenReturn(Mono.just(1L));
        when(eventRepository.findSurprisesHonoring("lea")).thenReturn(Flux.just(surprise));
        when(eventRepository.deleteByOrganizer("s1", "bob")).thenReturn(Mono.just(surprise));
        when(expenseRepository.findByEvent("s1")).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent("s1")).thenReturn(Mono.empty());
        when(noticeOutbox.forget("lea")).thenReturn(Mono.empty());

        StepVerifier.create(service.forget("lea")).expectNext(1L).verifyComplete();
        // The surprise's group in wely-chat is closed like any deleted event's.
        verify(deletionOutbox).record(any());
        verify(noticeOutbox).forget("lea");
    }

    @Test
    void forget_shouldStopAtTheFirstFailure_forTheErasureToBeRetried() {
        when(eventRepository.deleteBirthdayOf("lea"))
                .thenReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("mongo down")));

        StepVerifier.create(service.forget("lea")).expectError().verify();
        verify(noticeOutbox, never()).forget(anyString());
    }
}
