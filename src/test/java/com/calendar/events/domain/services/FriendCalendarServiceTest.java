package com.calendar.events.domain.services;

import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.EventRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendCalendarServiceTest {

    private static final Instant FROM = Instant.parse("2026-10-12T00:00:00Z");
    private static final TimeRange WEEK = new TimeRange(FROM, FROM.plus(Duration.ofDays(7)));

    @Mock
    private EventRepository eventRepository;
    @Mock
    private SocialGraph socialGraph;

    private FriendCalendarService service;

    @BeforeEach
    void setUp() {
        service = new FriendCalendarService(eventRepository, socialGraph);
    }

    private static Event event(String title, String organizer, Visibility visibility, int dayOffset) {
        Instant start = FROM.plus(Duration.ofDays(dayOffset)).plus(Duration.ofHours(18));
        return Event.builder().id(title).title(title).organizerId(organizer).visibility(visibility)
                .startDate(start).endDate(start.plus(Duration.ofHours(2)))
                .participantIds(new ArrayList<>(List.of("bob"))).build();
    }

    private static boolean hasCode(Throwable error, EventErrorCode code) {
        return error instanceof EventException e && e.getErrorCode() == code;
    }

    @Test
    void getFriendCalendar_shouldDiscloseEachEventAndSortThemByStart() {
        when(socialGraph.closenessBetween("alice", "bob")).thenReturn(Mono.just(Closeness.FRIEND));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of("bob")));
        when(eventRepository.findOccupying(Set.of("bob"), WEEK.start(), WEEK.end())).thenReturn(Flux.just(
                event("stranger's party", "carol", Visibility.FRIENDS, 3),
                event("bob's dinner", "bob", Visibility.FRIENDS, 1)));

        StepVerifier.create(service.getFriendCalendar("alice", "bob", WEEK))
                .expectNextMatches(entry -> "bob's dinner".equals(entry.title()))
                .expectNextMatches(entry -> entry.title() == null && entry.eventId() == null)
                .verifyComplete();
    }

    @Test
    void getFriendCalendar_shouldRevealMore_toACloserFriend() {
        when(socialGraph.closenessBetween("alice", "bob")).thenReturn(Mono.just(Closeness.CLOSE));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of("bob")));
        when(eventRepository.findOccupying(any(), any(), any()))
                .thenReturn(Flux.just(event("stranger's party", "carol", Visibility.FRIENDS, 3)));

        StepVerifier.create(service.getFriendCalendar("alice", "bob", WEEK).map(CalendarEntry::title))
                .expectNext("stranger's party")
                .verifyComplete();
    }

    @Test
    void getFriendCalendar_shouldRefuse_whenNotFriends() {
        when(socialGraph.closenessBetween("alice", "carol")).thenReturn(Mono.empty());
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of()));

        StepVerifier.create(service.getFriendCalendar("alice", "carol", WEEK))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.NOT_A_FRIEND))
                .verify();
        verify(eventRepository, never()).findOccupying(any(), any(), any());
    }

    @Test
    void getFriendCalendar_shouldPassOnASocialOutage() {
        when(socialGraph.closenessBetween("alice", "bob"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));
        when(socialGraph.friendIdsOf("alice")).thenReturn(Mono.just(Set.of()));

        StepVerifier.create(service.getFriendCalendar("alice", "bob", WEEK))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.SOCIAL_UNAVAILABLE))
                .verify();
    }

    @Test
    void getFriendCalendar_shouldRefuseTheCallersOwnCalendar() {
        StepVerifier.create(service.getFriendCalendar("alice", "alice", WEEK))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.INVALID_CALENDAR_REQUEST))
                .verify();
        verifyNoInteractions(socialGraph, eventRepository);
    }

    @Test
    void getFriendCalendar_shouldRefuseAWindowLongerThanTheMaximum() {
        TimeRange tooLong = new TimeRange(FROM, FROM.plus(FriendCalendarService.MAX_WINDOW).plusSeconds(1));

        StepVerifier.create(service.getFriendCalendar("alice", "bob", tooLong))
                .expectErrorMatches(e -> hasCode(e, EventErrorCode.INVALID_CALENDAR_REQUEST))
                .verify();
        verifyNoInteractions(socialGraph, eventRepository);
    }
}
