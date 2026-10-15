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

    // Bumps the version, so an edit opened before the receipt changed gets EXPENSE_CONFLICT.
    @Query("{ '_id': ?0, 'eventId': ?1 }")
    @Update("{ '$set': { 'receiptKey': ?2, 'receiptAddedBy': ?3 }, '$inc': { 'version': 1 } }")
    Mono<Long> setReceiptKey(String id, String eventId, String receiptKey, String addedBy);

    /** The user's own expenses whose receipt they attached; null also matches a missing attacher (legacy). */
    @Query("{ 'createdBy': ?0, 'receiptKey': { $ne: null }, 'receiptAddedBy': { $in: [ ?0, null ] } }")
    Flux<ExpenseEntity> findWithReceiptRecordedBy(String userId);

    // Only while the key is still the one read: a receipt replaced meanwhile is left alone.
    @Query("{ '_id': ?0, 'eventId': ?1, 'receiptKey': ?2 }")
    @Update("{ '$unset': { 'receiptKey': '', 'receiptAddedBy': '' }, '$inc': { 'version': 1 } }")
    Mono<Long> clearReceiptKey(String id, String eventId, String receiptKey);
}
