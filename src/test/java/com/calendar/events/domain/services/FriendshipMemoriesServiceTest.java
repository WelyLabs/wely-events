package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
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
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipMemoriesServiceTest {

    /** 18 October 2026, 10:00 in Paris. */
    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    /** 3 March 2025, 00:30 in Paris: the day in Paris, not in UTC. */
    private static final Instant SINCE = Instant.parse("2025-03-02T23:30:00Z");
    private static final String ME = "me";

    @Mock
    private EventRepository eventRepository;
    @Mock
    private FriendshipDirectory friendships;

    private FriendshipMemoriesService service;

    @BeforeEach
    void setUp() {
        service = new FriendshipMemoriesService(eventRepository, friendships, new DisplayNames(eventRepository),
                Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("Europe/Paris"));
    }

    private static Event outing(String id, String start, String image) {
        return Event.builder().id(id).title("Outing " + id).startDate(Instant.parse(start)).image(image).build();
    }

    private void leaIsMyFriend() {
        when(friendships.pairOf(ME, "lea")).thenReturn(Mono.just(new Friendship(ME, "lea", true, Closeness.INTIMATE, SINCE, 4)));
        when(eventRepository.findBirthdaysOf(List.of("lea")))
                .thenReturn(Flux.just(BirthdayService.birthdayEvent("lea", "Léa", MonthDay.of(6, 1))));
    }

    @Test
    void memoriesOf_shouldTellHowLongAndWhatTheyShared() {
        leaIsMyFriend();
        // Most recent first, as the repository reads them.
        List<Event> outings = List.of(
                outing("h", "2026-09-01T18:00:00Z", null),
                outing("g", "2026-08-01T18:00:00Z", "events/g/cover.jpg"),
                outing("f", "2026-07-01T18:00:00Z", null),
                outing("e", "2026-06-01T18:00:00Z", "/demo/events/picnic.jpg"),
                outing("d", "2026-05-01T18:00:00Z", null),
                outing("c", "2026-04-01T18:00:00Z", null),
                outing("b", "2026-03-01T18:00:00Z", null),
                outing("a", "2025-04-01T18:00:00Z", "events/a/cover.jpg"));
        when(eventRepository.findSharedOutings(ME, "lea", NOW)).thenReturn(Flux.fromIterable(outings));

        StepVerifier.create(service.memoriesOf(ME, "lea"))
                .assertNext(memories -> {
                    assertThat(memories.friendId()).isEqualTo("lea");
                    assertThat(memories.name()).isEqualTo("Léa");
                    assertThat(memories.closeness()).isEqualTo(Closeness.INTIMATE);
                    assertThat(memories.since()).isEqualTo(SINCE);
                    assertThat(memories.years()).isEqualTo(1);
                    // 3 March 2025 to 18 October 2026.
                    assertThat(memories.days()).isEqualTo(594);
                    assertThat(memories.sharedCount()).isEqualTo(8);
                    assertThat(memories.first().getId()).isEqualTo("a");
                    assertThat(memories.last().getId()).isEqualTo("h");
                    assertThat(memories.highlights()).extracting(Event::getId).containsExactly("g", "e", "a", "h", "f", "d");
                })
                .verifyComplete();
    }

    @Test
    void memoriesOf_shouldHaveNoFirstNorLast_withoutASharedOuting() {
        leaIsMyFriend();
        when(eventRepository.findSharedOutings(ME, "lea", NOW)).thenReturn(Flux.empty());

        StepVerifier.create(service.memoriesOf(ME, "lea"))
                .assertNext(memories -> {
                    assertThat(memories.sharedCount()).isZero();
                    assertThat(memories.first()).isNull();
                    assertThat(memories.last()).isNull();
                    assertThat(memories.highlights()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    void memoriesOf_shouldCountNothing_withoutAStartDate() {
        when(friendships.pairOf(ME, "lea")).thenReturn(Mono.just(new Friendship(ME, "lea", true, Closeness.FRIEND, null, 1)));
        when(eventRepository.findBirthdaysOf(List.of("lea"))).thenReturn(Flux.empty());
        when(eventRepository.findSharedOutings(ME, "lea", NOW)).thenReturn(Flux.empty());

        StepVerifier.create(service.memoriesOf(ME, "lea"))
                .assertNext(memories -> {
                    assertThat(memories.name()).isEqualTo(DisplayNames.FALLBACK);
                    assertThat(memories.years()).isZero();
                    assertThat(memories.days()).isZero();
                })
                .verifyComplete();
    }

    @Test
    void memoriesOf_shouldAnswerNotFound_forAStrangerOrAFormerFriend() {
        when(friendships.pairOf(ME, "stranger")).thenReturn(Mono.empty());
        when(friendships.pairOf(ME, "former")).thenReturn(Mono.just(new Friendship(ME, "former", false, null, null, 2)));

        StepVerifier.create(service.memoriesOf(ME, "stranger"))
                .expectErrorMatches(error -> error instanceof EventException e
                        && e.getErrorCode() == EventErrorCode.FRIEND_NOT_FOUND)
                .verify();
        StepVerifier.create(service.memoriesOf(ME, "former"))
                .expectErrorMatches(error -> error instanceof EventException e
                        && e.getErrorCode() == EventErrorCode.FRIEND_NOT_FOUND)
                .verify();
        verify(eventRepository, never()).findSharedOutings(any(), any(), any());
    }
}
