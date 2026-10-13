package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.ProfileSummary;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileSummaryServiceTest {

    /** Tuesday 13 October 2026, noon in Paris. */
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final String ME = "me";

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ExpenseRepository expenseRepository;

    private ProfileSummaryService service;

    @BeforeEach
    void setUp() {
        service = new ProfileSummaryService(eventRepository, expenseRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                ZoneId.of("Europe/Paris"));
    }

    private static Event event(String id, String start, String organizer, String location, String... participants) {
        Instant startDate = Instant.parse(start);
        return Event.builder().id(id).title("Event " + id).organizerId(organizer).startDate(startDate)
                .endDate(startDate.plusSeconds(3 * 3600)).location(location)
                .participantIds(new ArrayList<>(Arrays.asList(participants))).build();
    }

    private void involvedIn(Event... events) {
        when(eventRepository.findInvolving(ME)).thenReturn(Flux.just(events));
    }

    @Test
    void summaryOf_shouldCountThisYearsOutings_inTheUsersTimeZone() {
        involvedIn(
                // 31 Dec 2025 at 23:30 UTC is already 2026 in Paris.
                event("new-year", "2025-12-31T23:30:00Z", "lea", "Chez Léa", ME),
                event("last-year", "2025-12-31T22:30:00Z", "lea", "Chez Léa", ME),
                event("september", "2026-09-20T18:00:00Z", ME, "Parc"),
                // 30 Sep 2026 at 22:30 UTC is already October in Paris.
                event("october", "2026-09-30T22:30:00Z", "bob", "Ciné", ME),
                event("tomorrow", "2026-10-14T18:00:00Z", ME, "Bar"));
        when(expenseRepository.findByEvents(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> {
                    assertThat(summary.outingsThisYear()).isEqualTo(3);
                    assertThat(summary.outingsThisMonth()).isEqualTo(1);
                    assertThat(summary.organizedThisYear()).isEqualTo(1);
                    assertThat(summary.placesThisYear()).isEqualTo(3);
                })
                .verifyComplete();
    }

    @Test
    void summaryOf_shouldListTheNextOutingsFirstAndThePastNewestFirst() {
        involvedIn(
                event("p1", "2026-05-01T18:00:00Z", ME, "A"),
                event("p2", "2026-08-01T18:00:00Z", ME, "B"),
                event("f2", "2026-11-01T18:00:00Z", ME, "C"),
                event("f1", "2026-10-20T18:00:00Z", ME, "D"),
                event("f3", "2026-12-01T18:00:00Z", ME, "E"),
                event("f4", "2027-01-01T18:00:00Z", ME, "F"));
        when(expenseRepository.findByEvents(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> {
                    assertThat(summary.upcoming()).extracting(Event::getId).containsExactly("f1", "f2", "f3");
                    assertThat(summary.memories()).extracting(Event::getId).containsExactly("p2", "p1");
                    assertThat(summary.pastCount()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void summaryOf_shouldNotCountAnOutingStillUnderwayAsAMemory() {
        involvedIn(event("now", "2026-10-13T09:00:00Z", ME, "Bar"));
        when(expenseRepository.findByEvents(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> {
                    assertThat(summary.outingsThisYear()).isEqualTo(1);
                    assertThat(summary.memories()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("the circle counts each companion once per past outing, organizer included, most frequent first")
    void summaryOf_shouldRankTheCircleByOutingsTogether() {
        involvedIn(
                event("e1", "2026-03-01T18:00:00Z", "lea", "A", ME, "bob"),
                event("e2", "2026-04-01T18:00:00Z", "lea", "A", ME, "lea"),
                event("e3", "2026-05-01T18:00:00Z", ME, "B", "bob", "carol"),
                event("future", "2026-12-01T18:00:00Z", ME, "C", "zoe"));
        when(expenseRepository.findByEvents(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> assertThat(summary.circle()).containsExactly(
                        new ProfileSummary.Companion("bob", 2),
                        new ProfileSummary.Companion("lea", 2),
                        new ProfileSummary.Companion("carol", 1)))
                .verifyComplete();
    }

    @Test
    void summaryOf_shouldGroupPlacesByName_whateverTheCaseAndSpaces() {
        Event first = event("e1", "2026-03-01T18:00:00Z", ME, "Chez Léa");
        first.setCoordinates(new GeoPoint(45.77, 4.83));
        involvedIn(first,
                event("e2", "2026-04-01T18:00:00Z", ME, "  chez  léa "),
                event("e3", "2026-05-01T18:00:00Z", ME, "Parc de la Tête d'Or"));
        when(expenseRepository.findByEvents(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> {
                    assertThat(summary.places()).hasSize(2);
                    assertThat(summary.places().getFirst()).isEqualTo(
                            new ProfileSummary.Place("Chez Léa", new GeoPoint(45.77, 4.83), 2));
                })
                .verifyComplete();
    }

    @Test
    void summaryOf_shouldNameAPlaceWithoutCoordinates_afterItsLatestSpelling_tidied() {
        involvedIn(event("e1", "2026-03-01T18:00:00Z", ME, "bar du coin"),
                event("e2", "2026-04-01T18:00:00Z", ME, "  Bar   du coin "));
        when(expenseRepository.findByEvents(any())).thenReturn(Flux.empty());

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> assertThat(summary.places())
                        .containsExactly(new ProfileSummary.Place("Bar du coin", null, 2)))
                .verifyComplete();
    }

    @Test
    void summaryOf_shouldGiveTheBalanceOfEachEventWhereTheUserIsNotEven() {
        involvedIn(
                event("dinner", "2026-09-01T18:00:00Z", "lea", "A", ME),
                event("even", "2026-09-02T18:00:00Z", ME, "B", "lea"));
        Expense dinner = new Expense("x1", "dinner", "Resto", 3000, "EUR", "lea", "lea", ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share("lea", 0), new Share(ME, 0)), NOW, null, 0L);
        Expense even = new Expense("x2", "even", "Courses", 1000, "EUR", ME, ME, ExpenseKind.EXPENSE,
                SplitMode.EXACT, List.of(new Share(ME, 1000)), NOW, null, 0L);
        when(expenseRepository.findByEvents(any())).thenReturn(Flux.just(dinner, even));

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> assertThat(summary.balances())
                        .containsExactly(new ProfileSummary.EventBalance("dinner", "Event dinner", -1500)))
                .verifyComplete();
    }

    @Test
    void summaryOf_shouldBeEmpty_forSomeoneWithoutEvents_andReadNoExpenses() {
        when(eventRepository.findInvolving(ME)).thenReturn(Flux.empty());

        StepVerifier.create(service.summaryOf(ME))
                .assertNext(summary -> {
                    assertThat(summary.outingsThisYear()).isZero();
                    assertThat(summary.upcoming()).isEmpty();
                    assertThat(summary.circle()).isEmpty();
                    assertThat(summary.balances()).isEmpty();
                })
                .verifyComplete();
        verify(expenseRepository, never()).findByEvents(any());
    }
}
