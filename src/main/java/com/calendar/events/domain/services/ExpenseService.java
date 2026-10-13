package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseDraft;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Ledger;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * An event's shared expenses: who paid what, for whom, and how to settle up.
 *
 * <p>Only the event's members see or add expenses; to anyone else the event answers as missing,
 * as it does everywhere else. An expense is changed or deleted by whoever recorded it, or by the
 * organizer.
 */
public class ExpenseService {

    private final EventRepository eventRepository;
    private final ExpenseRepository expenseRepository;
    private final Clock clock;

    public ExpenseService(EventRepository eventRepository, ExpenseRepository expenseRepository, Clock clock) {
        this.eventRepository = eventRepository;
        this.expenseRepository = expenseRepository;
        this.clock = clock;
    }

    public Flux<Expense> list(String eventId, String viewerId) {
        return requireMember(eventId, viewerId).thenMany(Flux.defer(() -> expenseRepository.findByEvent(eventId)));
    }

    public Mono<Expense> add(String eventId, String requesterId, ExpenseDraft draft) {
        return requireMember(eventId, requesterId)
                .map(event -> requireMembers(event, new Expense(null, eventId, draft.title(), draft.amountCents(),
                        Expense.CURRENCY, draft.paidBy(), requesterId, ExpenseKind.EXPENSE, draft.split(),
                        draft.shares(), spentAt(draft), null)))
                .flatMap(expenseRepository::save);
    }

    /** A settlement is not edited: it is deleted and recorded again. */
    public Mono<Expense> update(String eventId, String expenseId, String requesterId, ExpenseDraft draft) {
        return requireMember(eventId, requesterId)
                .flatMap(event -> requireEditable(event, eventId, expenseId, requesterId)
                        .map(current -> requireMembers(event, new Expense(current.id(), eventId, draft.title(),
                                draft.amountCents(), current.currency(), draft.paidBy(), current.createdBy(),
                                current.kind(), draft.split(), draft.shares(), spentAt(draft),
                                draft.version() != null ? draft.version() : current.version()))))
                .flatMap(expenseRepository::save);
    }

    public Mono<Void> delete(String eventId, String expenseId, String requesterId) {
        return requireMember(eventId, requesterId)
                .flatMap(event -> requireAuthor(event, eventId, expenseId, requesterId))
                .flatMap(expense -> expenseRepository.delete(eventId, expense.id()));
    }

    public Mono<Ledger> ledger(String eventId, String viewerId) {
        return requireMember(eventId, viewerId)
                .then(Mono.defer(() -> expenseRepository.findByEvent(eventId).collectList()))
                .map(Ledger::of);
    }

    /** Records that {@code from} paid {@code to} back; only one of the two may say so. */
    public Mono<Expense> settle(String eventId, String requesterId, String fromUserId, String toUserId, long amountCents) {
        return requireMember(eventId, requesterId)
                .flatMap(event -> {
                    if (!requesterId.equals(fromUserId) && !requesterId.equals(toUserId)) {
                        return Mono.error(new EventException(EventErrorCode.EXPENSE_ACCESS_DENIED));
                    }
                    return Mono.just(requireMembers(event, Expense.settlement(eventId, fromUserId, toUserId,
                            amountCents, requesterId, clock.instant())));
                })
                .flatMap(expenseRepository::save);
    }

    /** Missing, or not theirs to see: both answer as missing. */
    private Mono<Event> requireMember(String eventId, String userId) {
        return eventRepository.findById(eventId)
                .filter(event -> event.involves(userId))
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EVENT_NOT_FOUND)));
    }

    private Mono<Expense> requireAuthor(Event event, String eventId, String expenseId, String requesterId) {
        return expenseRepository.findByIdInEvent(eventId, expenseId)
                .switchIfEmpty(Mono.error(new EventException(EventErrorCode.EXPENSE_NOT_FOUND)))
                .flatMap(expense -> Objects.equals(expense.createdBy(), requesterId)
                        || Objects.equals(event.getOrganizerId(), requesterId)
                        ? Mono.just(expense)
                        : Mono.error(new EventException(EventErrorCode.EXPENSE_ACCESS_DENIED)));
    }

    private Mono<Expense> requireEditable(Event event, String eventId, String expenseId, String requesterId) {
        return requireAuthor(event, eventId, expenseId, requesterId)
                .flatMap(expense -> expense.kind() == ExpenseKind.SETTLEMENT
                        ? Mono.error(new EventException(EventErrorCode.EXPENSE_INVALID))
                        : Mono.just(expense));
    }

    /** Whoever paid and whoever it was for must take part in the event now. */
    private static Expense requireMembers(Event event, Expense expense) {
        if (!expense.people().stream().allMatch(event::involves)) {
            throw new EventException(EventErrorCode.EXPENSE_INVALID);
        }
        return expense;
    }

    private Instant spentAt(ExpenseDraft draft) {
        return draft.spentAt() != null ? draft.spentAt() : clock.instant();
    }
}
