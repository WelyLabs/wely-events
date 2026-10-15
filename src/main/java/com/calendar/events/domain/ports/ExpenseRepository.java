package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.Expense;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

/** An event's expenses; a lookup by expense id also names the event, so an id from another event finds nothing. */
public interface ExpenseRepository {

    /** Newest first. */
    Flux<Expense> findByEvent(String eventId);

    Mono<Expense> findByIdInEvent(String eventId, String expenseId);

    /** Fails with EXPENSE_CONFLICT when the expense changed since its version was read. */
    Mono<Expense> save(Expense expense);

    Mono<Void> delete(String eventId, String expenseId);

    Mono<Void> deleteByEvent(String eventId);

    /** Sets the receipt key and its attacher (nulls clear both) and bumps the version; empty without such an expense. */
    Mono<Expense> setReceipt(String eventId, String expenseId, String receiptKey, String addedBy);

    /** The expenses of several events at once, in no particular order. */
    Flux<Expense> findByEvents(Collection<String> eventIds);

    /** Every expense this user recorded whose receipt they attached, or whose attacher is unknown (legacy). */
    Flux<Expense> findWithReceiptRecordedBy(String userId);

    /** Detaches the receipt and bumps the version only while it is still {@code receiptKey}; whether it did. */
    Mono<Boolean> clearReceipt(String eventId, String expenseId, String receiptKey);
}
