package com.calendar.events.domain.services;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.CommunityDirectory;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurpriseServiceTest {

    private static final Instant NOW = Instant.parse("2027-02-20T09:00:00Z");
    private static final Instant PARTY_START = Instant.parse("2027-03-12T18:00:00Z");
    private static final Instant PARTY_END = Instant.parse("2027-03-12T22:00:00Z");

    @Mock
    private EventRepository eventRepository;
    @Mock
    private SocialGraph socialGraph;
    @Mock
    private FriendshipDirectory friendships;
    @Mock
    private BirthdayNoticeOutbox noticeOutbox;
    @Mock
    private DeletedUserMarkers markers;
    @Mock
    private ExpenseRepository expenseRepository;
    @Mock
    private EventDeletionOutbox deletionOutbox;

    private SurpriseService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        EventService eventService = new EventService(eventRepository, socialGraph, expenseRepository,
                new PictureService(mock(MediaStore.class)), deletionOutbox,
                mock(CommunityDirectory.class), new AccountGuard(mock(DeletedUserMarkers.class)), clock);
        service = new SurpriseService(eventService, eventRepository, socialGraph, friendships, noticeOutbox,
                new AccountGuard(markers), clock, ZoneId.of("Europe/Paris"));
        lenient().when(noticeOutbox.record(any())).thenReturn(Mono.just(true));
    }

    private static Event request(String organizer, String honoree) {
        return Event.builder().title("Anniversaire surprise de Léa").organizerId(organizer).honoreeId(honoree)
                .kind(EventKind.SURPRISE).startDate(PARTY_START).endDate(PARTY_END).visibility(Visibility.PUBLIC)
                .communityId("c1").participantIds(new ArrayList<>()).build();
    }

    private static Event leasBirthday() {
        return BirthdayService.birthdayEvent("lea", "Léa", MonthDay.of(3, 12));
    }

    private void bobIsClose() {
        when(socialGraph.closenessBetween("bob", "lea")).thenReturn(Mono.just(Closeness.CLOSE));
        when(eventRepository.findBirthdayOf("lea")).thenReturn(Mono.just(leasBirthday()));
    }

    private static boolean hasCode(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    @Test
    void createSurprise_shouldStoreItHiddenFromTheHonoree_forTheirFriends_onThisYearsBirthday() {
        bobIsClose();
        when(eventRepository.findSurpriseStartingBetween(anyString(), any(), any())).thenReturn(Mono.empty());
        when(eventRepository.insertSurprise(any())).thenAnswer(call -> {
            Event saved = call.getArgument(0);
            saved.setId("s1");
            return Mono.just(saved);
        });
        when(friendships.friendsOf("lea")).thenReturn(Flux.empty());

        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob_l"))
                .assertNext(surprise -> {
                    assertThat(surprise.getKind()).isEqualTo(EventKind.SURPRISE);
                    assertThat(surprise.getHiddenFrom()).containsExactly("lea");
                    assertThat(surprise.getVisibility()).isEqualTo(Visibility.FRIENDS);
                    assertThat(surprise.getCommunityId()).isNull();
                    assertThat(surprise.getOccurrenceKey()).isEqualTo("lea:2027");
                    assertThat(surprise.getHonoreeName()).isEqualTo("Léa");
                    assertThat(surprise.getOrganizerName()).isEqualTo("bob_l");
                    assertThat(surprise.getParticipantIds()).containsExactly("bob");
                })
                .verifyComplete();
        verify(eventRepository).findSurpriseStartingBetween("lea", PARTY_START.minus(SurpriseService.SAME_BIRTHDAY_WINDOW),
                PARTY_START.plus(SurpriseService.SAME_BIRTHDAY_WINDOW));
    }

    @Test
    void createSurprise_shouldTellTheOtherCloseFriends_butNeitherTheOrganizerNorAPlainFriend() {
        bobIsClose();
        when(eventRepository.findSurpriseStartingBetween(anyString(), any(), any())).thenReturn(Mono.empty());
        when(eventRepository.insertSurprise(any())).thenAnswer(call -> {
            Event saved = call.getArgument(0);
            saved.setId("s1");
            return Mono.just(saved);
        });
        when(friendships.friendsOf("lea")).thenReturn(Flux.just(new Friend("bob", Closeness.CLOSE),
                new Friend("zoe", Closeness.INTIMATE), new Friend("hugo", Closeness.FRIEND), new Friend("lea", Closeness.CLOSE)));

        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob_l")).expectNextCount(1).verifyComplete();

        ArgumentCaptor<BirthdayNotice> notices = ArgumentCaptor.forClass(BirthdayNotice.class);
        verify(noticeOutbox, times(1)).record(notices.capture());
        assertThat(notices.getValue()).isEqualTo(new BirthdayNotice(BirthdayNoticeType.SURPRISE_CREATED, "zoe", "lea",
                "Léa", LocalDate.parse("2027-03-12"), 20, Closeness.INTIMATE, "s1", "bob", "bob_l"));
        // Neither the organizer's token nor wely-social: the projection alone names the honoree's friends.
        verify(socialGraph, never()).friendsOf(anyString());
    }

    @Test
    void createSurprise_shouldStillCreate_whenTheAnnouncementFails() {
        bobIsClose();
        when(eventRepository.findSurpriseStartingBetween(anyString(), any(), any())).thenReturn(Mono.empty());
        when(eventRepository.insertSurprise(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(friendships.friendsOf("lea")).thenReturn(Flux.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob_l")).expectNextCount(1).verifyComplete();
    }

    @Test
    void createSurprise_shouldRefuseAPlainFriendOrAStranger() {
        when(socialGraph.closenessBetween("bob", "lea")).thenReturn(Mono.just(Closeness.FRIEND));
        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob_l"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.SURPRISE_NOT_ALLOWED)).verify();

        when(socialGraph.closenessBetween("zoe", "lea")).thenReturn(Mono.empty());
        StepVerifier.create(service.createSurprise(request("zoe", "lea"), null, "zoe"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.SURPRISE_NOT_ALLOWED)).verify();
        verify(eventRepository, never()).insertSurprise(any());
    }

    @Test
    void createSurprise_shouldRefuseADateInThePast() {
        bobIsClose();
        Event surprise = request("bob", "lea");
        surprise.setStartDate(NOW.minus(Duration.ofDays(2)));
        surprise.setEndDate(NOW.minus(Duration.ofDays(2)).plus(Duration.ofHours(4)));

        StepVerifier.create(service.createSurprise(surprise, null, "bob_l"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_IN_PAST)).verify();
        verify(eventRepository, never()).insertSurprise(any());
    }

    @Test
    void createSurprise_shouldRefuseTheHonoree() {
        StepVerifier.create(service.createSurprise(request("lea", "lea"), null, "lea"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.SURPRISE_NOT_ALLOWED)).verify();
        verify(socialGraph, never()).closenessBetween(anyString(), anyString());
    }

    @Test
    void createSurprise_shouldRequireAnHonoree() {
        StepVerifier.create(service.createSurprise(request("bob", null), null, "bob"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_KIND_INVALID)).verify();
        StepVerifier.create(service.createSurprise(request("bob", " "), null, "bob"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.EVENT_KIND_INVALID)).verify();
    }

    @Test
    void createSurprise_shouldRefuseAnHonoreeWithoutABirthday() {
        when(socialGraph.closenessBetween("bob", "lea")).thenReturn(Mono.just(Closeness.INTIMATE));
        when(eventRepository.findBirthdayOf("lea")).thenReturn(Mono.empty());

        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NO_BIRTHDAY)).verify();
    }

    @Test
    void createSurprise_shouldNameTheExistingSurprise_whenOneIsWithin60Days() {
        bobIsClose();
        Event existing = Event.builder().id("s0").build();
        when(eventRepository.findSurpriseStartingBetween(anyString(), any(), any())).thenReturn(Mono.just(existing));

        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.SURPRISE_EXISTS)
                        && ((EventException) e).getProperties().equals(Map.of("eventId", "s0")))
                .verify();
        verify(eventRepository, never()).insertSurprise(any());
    }

    @Test
    void createSurprise_shouldNameTheWinner_whenAnotherOrganizerCreatedOneMeanwhile() {
        bobIsClose();
        when(eventRepository.findSurpriseStartingBetween(anyString(), any(), any())).thenReturn(Mono.empty());
        when(eventRepository.insertSurprise(any())).thenReturn(Mono.empty());
        when(eventRepository.findSurpriseByOccurrence("lea:2027")).thenReturn(Mono.just(Event.builder().id("s9").build()));

        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.SURPRISE_EXISTS)
                        && ((EventException) e).getProperties().equals(Map.of("eventId", "s9")))
                .verify();
    }

    @Test
    void createSurprise_shouldStillRefuse_whenTheWinnerVanishedMeanwhile() {
        bobIsClose();
        when(eventRepository.findSurpriseStartingBetween(anyString(), any(), any())).thenReturn(Mono.empty());
        when(eventRepository.insertSurprise(any())).thenReturn(Mono.empty());
        when(eventRepository.findSurpriseByOccurrence("lea:2027")).thenReturn(Mono.empty());

        StepVerifier.create(service.createSurprise(request("bob", "lea"), null, "bob"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.SURPRISE_EXISTS)
                        && ((EventException) e).getProperties().isEmpty())
                .verify();
    }

    @Test
    void createSurprise_shouldRefuseAnAllDaySurpriseNotOnWholeDays() {
        bobIsClose();
        Event allDay = request("bob", "lea");
        allDay.setAllDay(true);

        StepVerifier.create(service.createSurprise(allDay, null, "bob"))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.ALL_DAY_INVALID)).verify();
        verify(eventRepository, never()).insertSurprise(any());
    }

    private static Event surpriseFor(String honoree, String organizer, String... participants) {
        List<String> ids = new ArrayList<>(List.of(organizer));
        ids.addAll(List.of(participants));
        return Event.builder().id("s-" + honoree + "-" + organizer).title("Surprise").kind(EventKind.SURPRISE)
                .honoreeId(honoree).organizerId(organizer).hiddenFrom(new ArrayList<>(List.of(honoree)))
                .startDate(PARTY_START).endDate(PARTY_END).participantIds(ids).build();
    }

    private void noSurprisesFor(String honoree) {
        when(eventRepository.findSurprisesHonoring(honoree)).thenReturn(Flux.empty());
    }

    @Test
    void releaseAfterFriendshipEnded_shouldRemoveTheFormerFriendFromTheOthersSurprise() {
        Event surprise = surpriseFor("lea", "zoe", "bob", "max");
        when(eventRepository.findSurprisesHonoring("lea")).thenReturn(Flux.just(surprise));
        noSurprisesFor("bob");
        when(eventRepository.removeParticipant(surprise.getId(), "bob")).thenReturn(Mono.just(surprise));

        StepVerifier.create(service.releaseAfterFriendshipEnded("lea", "bob")).expectNext(1L).verifyComplete();
        verify(eventRepository, never()).handOver(anyString(), anyString(), anyString());
    }

    @Test
    void releaseAfterFriendshipEnded_shouldWorkBothWays_andLeaveSurprisesTheyAreNotOn() {
        noSurprisesFor("lea");
        Event bobsParty = surpriseFor("bob", "max", "lea");
        Event untouched = surpriseFor("bob", "zoe");
        untouched.setId("other");
        when(eventRepository.findSurprisesHonoring("bob")).thenReturn(Flux.just(bobsParty, untouched));
        when(eventRepository.removeParticipant(bobsParty.getId(), "lea")).thenReturn(Mono.just(bobsParty));

        StepVerifier.create(service.releaseAfterFriendshipEnded("lea", "bob")).expectNext(1L).verifyComplete();
        verify(eventRepository, never()).removeParticipant("other", "lea");
    }

    @Test
    void releaseAfterFriendshipEnded_shouldHandAnOrganizedSurpriseToTheEarliestParticipantStillTheHonoreesFriend() {
        Event surprise = surpriseFor("lea", "bob", "max", "zoe", "ana");
        when(eventRepository.findSurprisesHonoring("lea")).thenReturn(Flux.just(surprise));
        noSurprisesFor("bob");
        // Max is no longer Léa's friend, Zoé's account is deleted: Ana takes over.
        when(friendships.friendsOf("lea")).thenReturn(Flux.just(new Friend("bob", Closeness.CLOSE),
                new Friend("zoe", Closeness.CLOSE), new Friend("ana", Closeness.FRIEND)));
        when(markers.deletedAmong(surprise.getParticipantIds())).thenReturn(Mono.just(java.util.Set.of("zoe")));
        when(eventRepository.handOver(surprise.getId(), "bob", "ana")).thenReturn(Mono.just(surprise));

        StepVerifier.create(service.releaseAfterFriendshipEnded("bob", "lea")).expectNext(1L).verifyComplete();
        verify(eventRepository, never()).deleteByOrganizer(anyString(), anyString());
    }

    @Test
    void releaseAfterFriendshipEnded_shouldDeleteAnOrganizedSurprise_whenNoParticipantIsStillTheHonoreesFriend() {
        Event surprise = surpriseFor("lea", "bob", "max");
        when(eventRepository.findSurprisesHonoring("lea")).thenReturn(Flux.just(surprise));
        noSurprisesFor("bob");
        when(friendships.friendsOf("lea")).thenReturn(Flux.just(new Friend("bob", Closeness.CLOSE)));
        when(markers.deletedAmong(surprise.getParticipantIds())).thenReturn(Mono.just(java.util.Set.of()));
        when(eventRepository.deleteByOrganizer(surprise.getId(), "bob")).thenReturn(Mono.just(surprise));
        when(deletionOutbox.record(any())).thenReturn(Mono.empty());
        when(expenseRepository.findByEvent(surprise.getId())).thenReturn(Flux.empty());
        when(expenseRepository.deleteByEvent(surprise.getId())).thenReturn(Mono.empty());

        StepVerifier.create(service.releaseAfterFriendshipEnded("lea", "bob")).expectNext(1L).verifyComplete();
        verify(deletionOutbox).record(any());
        verify(eventRepository, never()).handOver(anyString(), anyString(), anyString());
    }

    @Test
    void releaseAfterFriendshipEnded_shouldFail_whenTheSurprisesCannotBeRead() {
        when(eventRepository.findSurprisesHonoring("lea")).thenReturn(Flux.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.releaseAfterFriendshipEnded("lea", "bob")).expectError(IllegalStateException.class).verify();
    }
}
