package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.ExpenseEntity;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

/** Every query names the event, so an expense id taken from another event matches nothing. */
@Repository
public interface ReactiveExpenseMongoRepository extends ReactiveMongoRepository<ExpenseEntity, String> {

    Flux<ExpenseEntity> findAllByEventIdOrderBySpentAtDesc(String eventId);

    Mono<ExpenseEntity> findByIdAndEventId(String id, String eventId);

    Mono<Long> deleteByIdAndEventId(String id, String eventId);

    Mono<Long> deleteAllByEventId(String eventId);

    Flux<ExpenseEntity> findAllByEventIdIn(Collection<String> eventIds);

    // Bumps the version: an edit form opened before the receipt changed gets EXPENSE_CONFLICT
    // instead of a full save that would write the old receipt key back.
    @Query("{ '_id': ?0, 'eventId': ?1 }")
    @Update("{ '$set': { 'receiptKey': ?2 }, '$inc': { 'version': 1 } }")
    Mono<Long> setReceiptKey(String id, String eventId, String receiptKey);
}
