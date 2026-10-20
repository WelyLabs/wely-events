package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FreeSlotServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private SocialGraph socialGraph;

    @InjectMocks
    private FreeSlotService service;

    private static final Instant TEN = Instant.parse("2026-10-17T10:00:00Z");
    private static final TimeRange WINDOW = new TimeRange(TEN, TEN.plus(Duration.ofHours(8)));
    private static final Duration HOUR = Duration.ofHours(1);

    private static boolean failsWith(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException e && e.getErrorCode() == code;
    }

    private static boolean failsWith(Throwable error, TechnicalErrorCode code) {
        return error instanceof TechnicalException e && e.getErrorCode() == code;
    }

    @Test
    void findCommonFreeRanges_shouldSearchTheRequesterAndTheFriends_andReturnTheGaps() {
        when(socialGraph.friendIdsOf("me")).thenReturn(Mono.just(Set.of("lea", "hugo", "ines")));
        // Léa is busy 12:00-13:00; Hugo organizes an event with no end at 15:00, so 15:00-16:00.
        Event lea = Event.builder().startDate(TEN.plus(Duration.ofHours(2))).endDate(TEN.plus(Duration.ofHours(3))).build();
        Event hugo = Event.builder().startDate(TEN.plus(Duration.ofHours(5))).build();
        when(eventRepository.findOccupying(Set.of("me", "lea", "hugo"), WINDOW.start(), WINDOW.end(), "me"))
                .thenReturn(Flux.just(lea, hugo));

        StepVerifier.create(service.findCommonFreeRanges("me", Set.of("lea", "hugo"), WINDOW, HOUR))
                .expectNext(new TimeRange(TEN, TEN.plus(Duration.ofHours(2))))
                .expectNext(new TimeRange(TEN.plus(Duration.ofHours(3)), TEN.plus(Duration.ofHours(5))))
                .expectNext(new TimeRange(TEN.plus(Duration.ofHours(6)), WINDOW.end()))
                .verifyComplete();
    }

    @Test
    void findCommonFreeRanges_shouldRefuseSomeoneWhoIsNotAFriend_withoutReadingTheirEvents() {
        when(socialGraph.friendIdsOf("me")).thenReturn(Mono.just(Set.of("lea")));

        StepVerifier.create(service.findCommonFreeRanges("me", Set.of("lea", "stranger"), WINDOW, HOUR))
                .expectErrorMatches(e -> failsWith(e, BusinessErrorCode.NOT_A_FRIEND))
                .verify();

        verify(eventRepository, never()).findOccupying(anyCollection(), any(), any(), any());
    }

    @Test
    void findCommonFreeRanges_shouldFailClosed_whenTheFriendListIsEmpty() {
        when(socialGraph.friendIdsOf("me")).thenReturn(Mono.empty());

        StepVerifier.create(service.findCommonFreeRanges("me", Set.of("lea"), WINDOW, HOUR))
                .expectErrorMatches(e -> failsWith(e, BusinessErrorCode.NOT_A_FRIEND))
                .verify();

        verify(eventRepository, never()).findOccupying(anyCollection(), any(), any(), any());
    }

    @Test
    void findCommonFreeRanges_shouldPropagateASocialOutage() {
        when(socialGraph.friendIdsOf("me"))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.SOCIAL_UNAVAILABLE)));

        StepVerifier.create(service.findCommonFreeRanges("me", Set.of("lea"), WINDOW, HOUR))
                .expectErrorMatches(e -> failsWith(e, TechnicalErrorCode.SOCIAL_UNAVAILABLE))
                .verify();
    }

    @Test
    void findCommonFreeRanges_shouldRejectAnInvalidSearch_beforeAskingAnyone() {
        Set<String> tooMany = IntStream.range(0, 21).mapToObj(i -> "f" + i).collect(Collectors.toSet());
        TimeRange tooLong = new TimeRange(TEN, TEN.plus(FreeSlotService.MAX_WINDOW).plusSeconds(1));

        for (var search : new Object[][]{
                {Set.of(), WINDOW, HOUR},                                // nobody to meet
                {tooMany, WINDOW, HOUR},                                  // more than 20 friends
                {new HashSet<>(Set.of("lea", "me")), WINDOW, HOUR},       // the requester listed as a friend
                {Set.of("lea"), WINDOW, Duration.ofMinutes(10)},          // shorter than 15 minutes
                {Set.of("lea"), WINDOW, Duration.ofHours(9)},             // longer than the period
                {Set.of("lea"), tooLong, HOUR}}) {                        // period over 62 days
            @SuppressWarnings("unchecked")
            Set<String> friends = (Set<String>) search[0];
            StepVerifier.create(service.findCommonFreeRanges("me", friends, (TimeRange) search[1], (Duration) search[2]))
                    .expectErrorMatches(e -> failsWith(e, BusinessErrorCode.INVALID_SLOT_SEARCH))
                    .verify();
        }

        verify(socialGraph, never()).friendIdsOf(any());
        verify(eventRepository, never()).findOccupying(anyCollection(), any(), any(), any());
    }

    @Test
    void findCommonFreeRanges_shouldAcceptAMonthGrid_ofSixWeeks() {
        TimeRange sixWeeks = new TimeRange(TEN, TEN.plus(Duration.ofDays(42)));
        when(socialGraph.friendIdsOf("me")).thenReturn(Mono.just(Set.of("lea")));
        when(eventRepository.findOccupying(anyCollection(), any(), any(), any())).thenReturn(Flux.empty());

        StepVerifier.create(service.findCommonFreeRanges("me", Set.of("lea"), sixWeeks, HOUR))
                .expectNext(sixWeeks)
                .verifyComplete();
    }
}
