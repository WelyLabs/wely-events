package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.FriendOverview;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendOverviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");

    @Mock
    private EventRepository eventRepository;
    @Mock
    private SocialGraph socialGraph;

    private FriendOverviewService service;

    @BeforeEach
    void setUp() {
        service = new FriendOverviewService(eventRepository, socialGraph, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** alice looks at bob's page; her friends are bob and dave, not carol. */
    private void friends() {
        when(socialGraph.closenessBetween("alice", "bob")).thenReturn(Mono.just(Closeness.FRIEND));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of("bob", "dave")));
    }

    private void nothingTogether() {
        when(eventRepository.countPastInvolvingBoth("alice", "bob", NOW)).thenReturn(Mono.just(0L));
        when(eventRepository.findUpcomingInvolvingBoth(anyString(), anyString(), any(), anyInt())).thenReturn(Flux.empty());
        when(eventRepository.findPastInvolvingBoth(anyString(), anyString(), any(), anyInt())).thenReturn(Flux.empty());
    }

    private static Event event(String title, String organizer, Visibility visibility, String... participants) {
        Instant start = NOW.plus(Duration.ofDays(1));
        return Event.builder().id(title).title(title).organizerId(organizer).visibility(visibility)
                .startDate(start).endDate(start.plus(Duration.ofHours(2)))
                .participantIds(new ArrayList<>(List.of(participants))).build();
    }

    private static boolean hasCode(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException e && e.getErrorCode() == code;
    }

    private static boolean hasCode(Throwable error, TechnicalErrorCode code) {
        return error instanceof TechnicalException e && e.getErrorCode() == code;
    }

    private List<String> friendUpcomingTitles() {
        return service.overviewOf("alice", "bob")
                .map(overview -> overview.friendUpcoming().stream().map(Event::getTitle).toList())
                .block();
    }

    @Test
    void overviewOf_shouldGatherWhatTheTwoShare_withTheLimitsOfThePage() {
        friends();
        Event next = event("next", "alice", Visibility.FRIENDS, "bob");
        Event memory = event("memory", "bob", Visibility.PRIVATE, "alice");
        when(eventRepository.countPastInvolvingBoth("alice", "bob", NOW)).thenReturn(Mono.just(12L));
        when(eventRepository.findUpcomingInvolvingBoth("alice", "bob", NOW, 5)).thenReturn(Flux.just(next));
        when(eventRepository.findPastInvolvingBoth("alice", "bob", NOW, 6)).thenReturn(Flux.just(memory));
        when(eventRepository.findUpcomingInvolving("bob", "alice", NOW)).thenReturn(Flux.empty());

        StepVerifier.create(service.overviewOf("alice", "bob"))
                .assertNext(overview -> {
                    assertThat(overview.outingsTogether()).isEqualTo(12);
                    assertThat(overview.upcomingTogether()).containsExactly(next);
                    assertThat(overview.memoriesTogether()).containsExactly(memory);
                    assertThat(overview.friendUpcoming()).isEmpty();
                })
                .verifyComplete();
        assertThat(FriendOverviewService.UPCOMING_TOGETHER_SIZE).isEqualTo(5);
        assertThat(FriendOverviewService.MEMORIES_TOGETHER_SIZE).isEqualTo(6);
    }

    @Test
    void overviewOf_shouldKeepOnlyTheFriendsEventsTheCallerMaySee() {
        friends();
        nothingTogether();
        when(eventRepository.findUpcomingInvolving("bob", "alice", NOW)).thenReturn(Flux.just(
                event("bob's private dinner", "bob", Visibility.PRIVATE),
                event("carol's friends-only party", "carol", Visibility.FRIENDS, "bob"),
                event("carol's public concert", "carol", Visibility.PUBLIC, "bob"),
                event("bob's friends-only hike", "bob", Visibility.FRIENDS),
                event("dave's friends-only game", "dave", Visibility.FRIENDS, "bob"),
                event("before visibility existed, by carol", "carol", null, "bob")));

        assertThat(friendUpcomingTitles()).containsExactly(
                "carol's public concert", "bob's friends-only hike", "dave's friends-only game");
    }

    @Test
    void overviewOf_shouldLeaveOutTheFriendsEventsTheCallerIsAlreadyOn() {
        friends();
        nothingTogether();
        when(eventRepository.findUpcomingInvolving("bob", "alice", NOW)).thenReturn(Flux.just(
                event("alice joined", "bob", Visibility.PUBLIC, "alice"),
                event("alice organizes", "alice", Visibility.PUBLIC, "bob"),
                event("open to alice", "bob", Visibility.PUBLIC)));

        assertThat(friendUpcomingTitles()).containsExactly("open to alice");
    }

    @Test
    void overviewOf_shouldIncludeTheFriendsOpenPolls() {
        friends();
        nothingTogether();
        Instant start = NOW.plus(Duration.ofDays(2));
        Event polling = event("when shall we meet?", "bob", Visibility.FRIENDS);
        polling.setPoll(Poll.open(List.of(new PollSlot(null, start, start.plusSeconds(3600)),
                new PollSlot(null, start.plus(Duration.ofDays(1)), start.plus(Duration.ofDays(1)).plusSeconds(3600))), NOW));
        when(eventRepository.findUpcomingInvolving("bob", "alice", NOW)).thenReturn(Flux.just(polling));

        StepVerifier.create(service.overviewOf("alice", "bob"))
                .assertNext(overview -> assertThat(overview.friendUpcoming()).singleElement()
                        .satisfies(event -> assertThat(event.isPolling()).isTrue()))
                .verifyComplete();
    }

    @Test
    void overviewOf_shouldKeepTheFriendsFirstFiveVisibleEvents_inTheOrderRead_andStopReading() {
        friends();
        nothingTogether();
        AtomicInteger read = new AtomicInteger();
        List<Event> agenda = IntStream.range(0, 20)
                .mapToObj(i -> event("event " + i, "bob", i % 2 == 0 ? Visibility.PUBLIC : Visibility.PRIVATE))
                .toList();
        when(eventRepository.findUpcomingInvolving("bob", "alice", NOW))
                .thenReturn(Flux.fromIterable(agenda).doOnNext(event -> read.incrementAndGet()));

        assertThat(friendUpcomingTitles()).containsExactly("event 0", "event 2", "event 4", "event 6", "event 8");
        assertThat(FriendOverviewService.FRIEND_UPCOMING_SIZE).isEqualTo(5);
        // The fifth match is the ninth event: nothing after it is pulled.
        assertThat(read.get()).isEqualTo(9);
    }

    @Test
    void overviewOf_shouldRefuse_whenNotFriends() {
        when(socialGraph.closenessBetween("alice", "carol")).thenReturn(Mono.empty());
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of()));

        StepVerifier.create(service.overviewOf("alice", "carol"))
                .expectErrorMatches(e -> hasCode(e, BusinessErrorCode.NOT_A_FRIEND))
                .verify();
        verify(eventRepository, never()).findUpcomingInvolving(any(), any(), any());
        verify(eventRepository, never()).countPastInvolvingBoth(any(), any(), any());
        verify(eventRepository, never()).findPastInvolvingBoth(any(), any(), any(), anyInt());
        verify(eventRepository, never()).findUpcomingInvolvingBoth(any(), any(), any(), anyInt());
    }

    @Test
    void overviewOf_shouldRefuseTheCallersOwnPage() {
        StepVerifier.create(service.overviewOf("alice", "alice"))
                .expectErrorMatches(e -> hasCode(e, BusinessErrorCode.INVALID_CALENDAR_REQUEST))
                .verify();
        verifyNoInteractions(socialGraph, eventRepository);
    }

    @Test
    void overviewOf_shouldPassOnASocialOutage() {
        when(socialGraph.closenessBetween("alice", "bob"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.SOCIAL_UNAVAILABLE)));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of()));

        StepVerifier.create(service.overviewOf("alice", "bob"))
                .expectErrorMatches(e -> hasCode(e, TechnicalErrorCode.SOCIAL_UNAVAILABLE))
                .verify();
    }

    @Test
    void overviewOf_shouldShowOnlyPublicEvents_whenTheCallerHasNoFriendList() {
        when(socialGraph.closenessBetween("alice", "bob")).thenReturn(Mono.just(Closeness.FRIEND));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.empty());
        nothingTogether();
        when(eventRepository.findUpcomingInvolving("bob", "alice", NOW)).thenReturn(Flux.just(
                event("bob's friends-only hike", "bob", Visibility.FRIENDS),
                event("public", "bob", Visibility.PUBLIC)));

        assertThat(friendUpcomingTitles()).containsExactly("public");
    }
}
