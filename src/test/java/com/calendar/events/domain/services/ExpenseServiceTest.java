package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseDraft;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
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
import java.time.Instant;
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
class ExpenseServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private static final String EVENT = "ev-1";

    @Mock
    private EventRepository eventRepository;

    @Mock
    private ExpenseRepository expenseRepository;

    private ExpenseService service;

    /** Olivia organizes; Alice and Bob take part; Mallory does not. */
    private final Event event = Event.builder().id(EVENT).organizerId("olivia")
            .participantIds(new ArrayList<>(List.of("alice", "bob"))).build();

    @BeforeEach
    void setUp() {
        service = new ExpenseService(eventRepository, expenseRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ExpenseDraft draft(String paidBy, String... people) {
        return new ExpenseDraft("Courses", 3000, paidBy, SplitMode.EQUAL,
                Arrays.stream(people).map(id -> new Share(id, 0)).toList(), null, null);
    }

    private static Expense stored(String id, String createdBy, ExpenseKind kind) {
        return new Expense(id, EVENT, "Courses", 3000, "EUR", createdBy, createdBy, kind, SplitMode.EXACT,
                List.of(new Share(createdBy.equals("alice") ? "bob" : "alice", 3000)), NOW, 3L);
    }

    private static boolean failsWith(Throwable error, EventErrorCode code) {
        return error instanceof EventException exception && exception.getErrorCode() == code;
    }

    private void eventExists() {
        when(eventRepository.findById(EVENT)).thenReturn(Mono.just(event));
    }

    private void savesWhatItIsGiven() {
        when(expenseRepository.save(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
    }

    @Test
    void list_shouldReturnTheExpenses_toAMember() {
        eventExists();
        Expense expense = stored("x1", "alice", ExpenseKind.EXPENSE);
        when(expenseRepository.findByEvent(EVENT)).thenReturn(Flux.just(expense));

        StepVerifier.create(service.list(EVENT, "bob")).expectNext(expense).verifyComplete();
    }

    @Test
    void list_shouldAnswerNotFound_toSomeoneOutsideTheEvent() {
        eventExists();

        StepVerifier.create(service.list(EVENT, "mallory"))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
        verify(expenseRepository, never()).findByEvent(any());
    }

    @Test
    void list_shouldAnswerNotFound_whenTheEventDoesNotExist() {
        when(eventRepository.findById(EVENT)).thenReturn(Mono.empty());

        StepVerifier.create(service.list(EVENT, "alice"))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void add_shouldRecordTheRequesterAsAuthor_andStampTheTime() {
        eventExists();
        savesWhatItIsGiven();

        StepVerifier.create(service.add(EVENT, "bob", draft("alice", "alice", "bob", "olivia")))
                .assertNext(expense -> {
                    assertThat(expense.createdBy()).isEqualTo("bob");
                    assertThat(expense.paidBy()).isEqualTo("alice");
                    assertThat(expense.kind()).isEqualTo(ExpenseKind.EXPENSE);
                    assertThat(expense.spentAt()).isEqualTo(NOW);
                    assertThat(expense.eventId()).isEqualTo(EVENT);
                })
                .verifyComplete();
    }

    @Test
    void add_shouldRefuseSomeoneWhoIsNotAMember() {
        eventExists();

        StepVerifier.create(service.add(EVENT, "alice", draft("alice", "alice", "mallory")))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_INVALID))
                .verify();
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void add_shouldRefuseAPayerWhoIsNotAMember() {
        eventExists();

        StepVerifier.create(service.add(EVENT, "alice", draft("mallory", "alice")))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_INVALID))
                .verify();
    }

    @Test
    void add_shouldAnswerNotFound_toSomeoneOutsideTheEvent() {
        eventExists();

        StepVerifier.create(service.add(EVENT, "mallory", draft("mallory", "mallory")))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void update_shouldKeepTheAuthorAndKind_andUseTheVersionTheClientRead() {
        eventExists();
        savesWhatItIsGiven();
        when(expenseRepository.findByIdInEvent(EVENT, "x1")).thenReturn(Mono.just(stored("x1", "alice", ExpenseKind.EXPENSE)));
        ExpenseDraft edit = new ExpenseDraft("Pizzas", 4500, "alice", SplitMode.EQUAL,
                List.of(new Share("alice", 0), new Share("bob", 0)), null, 2L);

        StepVerifier.create(service.update(EVENT, "x1", "alice", edit))
                .assertNext(expense -> {
                    assertThat(expense.id()).isEqualTo("x1");
                    assertThat(expense.title()).isEqualTo("Pizzas");
                    assertThat(expense.createdBy()).isEqualTo("alice");
                    assertThat(expense.version()).isEqualTo(2L);
                })
                .verifyComplete();
    }

    @Test
    void update_shouldKeepTheStoredVersion_whenTheClientSendsNone() {
        eventExists();
        savesWhatItIsGiven();
        when(expenseRepository.findByIdInEvent(EVENT, "x1")).thenReturn(Mono.just(stored("x1", "alice", ExpenseKind.EXPENSE)));

        StepVerifier.create(service.update(EVENT, "x1", "alice", draft("alice", "alice", "bob")))
                .assertNext(expense -> assertThat(expense.version()).isEqualTo(3L))
                .verifyComplete();
    }

    @Test
    void update_shouldBeAllowedToTheOrganizer() {
        eventExists();
        savesWhatItIsGiven();
        when(expenseRepository.findByIdInEvent(EVENT, "x1")).thenReturn(Mono.just(stored("x1", "alice", ExpenseKind.EXPENSE)));

        StepVerifier.create(service.update(EVENT, "x1", "olivia", draft("alice", "alice", "bob")))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void update_shouldRejectWhenRequesterIsNeitherAuthorNorOrganizer() {
        eventExists();
        when(expenseRepository.findByIdInEvent(EVENT, "x1")).thenReturn(Mono.just(stored("x1", "alice", ExpenseKind.EXPENSE)));

        StepVerifier.create(service.update(EVENT, "x1", "bob", draft("alice", "alice", "bob")))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_ACCESS_DENIED))
                .verify();
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void update_shouldRefuseToEditASettlement() {
        eventExists();
        when(expenseRepository.findByIdInEvent(EVENT, "s1")).thenReturn(Mono.just(stored("s1", "alice", ExpenseKind.SETTLEMENT)));

        StepVerifier.create(service.update(EVENT, "s1", "alice", draft("alice", "alice", "bob")))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_INVALID))
                .verify();
    }

    @Test
    void update_shouldAnswerNotFound_forAnExpenseOfAnotherEvent() {
        eventExists();
        when(expenseRepository.findByIdInEvent(EVENT, "elsewhere")).thenReturn(Mono.empty());

        StepVerifier.create(service.update(EVENT, "elsewhere", "alice", draft("alice", "alice")))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_NOT_FOUND))
                .verify();
    }

    @Test
    void delete_shouldRemoveTheAuthorsExpense() {
        eventExists();
        when(expenseRepository.findByIdInEvent(EVENT, "x1")).thenReturn(Mono.just(stored("x1", "alice", ExpenseKind.EXPENSE)));
        when(expenseRepository.delete(EVENT, "x1")).thenReturn(Mono.empty());

        StepVerifier.create(service.delete(EVENT, "x1", "alice")).verifyComplete();

        verify(expenseRepository).delete(EVENT, "x1");
    }

    @Test
    void delete_shouldRejectWhenRequesterIsNeitherAuthorNorOrganizer() {
        eventExists();
        when(expenseRepository.findByIdInEvent(EVENT, "x1")).thenReturn(Mono.just(stored("x1", "alice", ExpenseKind.EXPENSE)));

        StepVerifier.create(service.delete(EVENT, "x1", "bob"))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_ACCESS_DENIED))
                .verify();
        verify(expenseRepository, never()).delete(any(), any());
    }

    @Test
    void ledger_shouldSumTheEventsExpenses() {
        eventExists();
        when(expenseRepository.findByEvent(EVENT)).thenReturn(Flux.just(
                new Expense("x1", EVENT, "Courses", 3000, "EUR", "alice", "alice", ExpenseKind.EXPENSE,
                        SplitMode.EQUAL, List.of(new Share("alice", 0), new Share("bob", 0), new Share("olivia", 0)), NOW, 0L)));

        StepVerifier.create(service.ledger(EVENT, "bob"))
                .assertNext(ledger -> {
                    assertThat(ledger.totalCents()).isEqualTo(3000);
                    assertThat(ledger.balances()).containsEntry("alice", 2000L).containsEntry("bob", -1000L);
                })
                .verifyComplete();
    }

    @Test
    void ledger_shouldAnswerNotFound_toSomeoneOutsideTheEvent() {
        eventExists();

        StepVerifier.create(service.ledger(EVENT, "mallory"))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EVENT_NOT_FOUND))
                .verify();
    }

    @Test
    void settle_shouldRecordTheRepayment_whenOneOfTheTwoAsks() {
        eventExists();
        savesWhatItIsGiven();

        StepVerifier.create(service.settle(EVENT, "alice", "bob", "alice", 1000))
                .assertNext(settlement -> {
                    assertThat(settlement.kind()).isEqualTo(ExpenseKind.SETTLEMENT);
                    assertThat(settlement.paidBy()).isEqualTo("bob");
                    assertThat(settlement.createdBy()).isEqualTo("alice");
                    assertThat(settlement.spentAt()).isEqualTo(NOW);
                })
                .verifyComplete();
    }

    @Test
    void settle_shouldRejectWhenRequesterIsNotPartOfTheRepayment() {
        eventExists();

        StepVerifier.create(service.settle(EVENT, "olivia", "bob", "alice", 1000))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_ACCESS_DENIED))
                .verify();
        verify(expenseRepository, never()).save(any());
    }

    @Test
    void settle_shouldRefuseARepaymentToSomeoneOutsideTheEvent() {
        eventExists();

        StepVerifier.create(service.settle(EVENT, "alice", "alice", "mallory", 1000))
                .expectErrorMatches(error -> failsWith(error, EventErrorCode.EXPENSE_INVALID))
                .verify();
    }

    @Test
    void add_shouldSaveExactlyOneExpense() {
        eventExists();
        savesWhatItIsGiven();
        ArgumentCaptor<Expense> saved = ArgumentCaptor.forClass(Expense.class);

        StepVerifier.create(service.add(EVENT, "alice", draft("alice", "alice", "bob"))).expectNextCount(1).verifyComplete();

        verify(expenseRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isNull();
    }
}
