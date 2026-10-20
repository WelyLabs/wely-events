package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.ports.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.MonthDay;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisplayNamesTest {

    @Mock
    private EventRepository eventRepository;

    private DisplayNames names;

    @BeforeEach
    void setUp() {
        names = new DisplayNames(eventRepository);
    }

    private static Event birthdayOf(String userId, String name) {
        Event birthday = BirthdayService.birthdayEvent(userId, "x", MonthDay.of(3, 12));
        birthday.setHonoreeName(name);
        return birthday;
    }

    @Test
    void of_shouldReadEachNameFromTheBirthdays_leavingOutBlankOnes() {
        when(eventRepository.findBirthdaysOf(List.of("lea", "hugo", "max")))
                .thenReturn(Flux.just(birthdayOf("lea", "Léa"), birthdayOf("hugo", "  ")));

        StepVerifier.create(names.of(List.of("lea", "hugo", "max"))).expectNext(Map.of("lea", "Léa")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void of_shouldReadNothing_forNobody() {
        StepVerifier.create(names.of(List.of())).expectNext(Map.of()).expectComplete().verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).findBirthdaysOf(any());
    }

    @Test
    void of_shouldNameOneUser_orFallBack() {
        when(eventRepository.findBirthdaysOf(List.of("lea"))).thenReturn(Flux.just(birthdayOf("lea", "Léa")));
        when(eventRepository.findBirthdaysOf(List.of("max"))).thenReturn(Flux.empty());

        StepVerifier.create(names.of("lea")).expectNext("Léa").expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(names.of("max")).expectNext(DisplayNames.FALLBACK).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void of_shouldPassAReadFailureOn() {
        when(eventRepository.findBirthdaysOf(List.of("lea"))).thenReturn(Flux.error(new IllegalStateException("mongo down")));

        StepVerifier.create(names.of("lea")).expectError(IllegalStateException.class).verify(Duration.ofSeconds(10));
    }

    @Test
    void nameIn_shouldFallBackToUnAmi() {
        assertThat(DisplayNames.nameIn(Map.of("lea", "Léa"), "lea")).isEqualTo("Léa");
        assertThat(DisplayNames.nameIn(Map.of(), "max")).isEqualTo("Un ami");
    }
}
