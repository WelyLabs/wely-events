package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Expense;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

/** An event's expenses. Every lookup names the event too, so an id from another event finds nothing. */
public interface ExpenseRepository {

    /** Newest first. */
    Flux<Expense> findByEvent(String eventId);

    Mono<Expense> findByIdInEvent(String eventId, String expenseId);

    /** Fails with EXPENSE_CONFLICT when the expense changed since its version was read. */
    Mono<Expense> save(Expense expense);

    Mono<Void> delete(String eventId, String expenseId);

    Mono<Void> deleteByEvent(String eventId);

    /** The expenses of several events at once, in no particular order. */
    Flux<Expense> findByEvents(Collection<String> eventIds);
}
