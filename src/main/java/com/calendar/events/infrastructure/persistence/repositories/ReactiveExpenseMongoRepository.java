package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.ExpenseEntity;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Every query names the event, so an expense id taken from another event matches nothing. */
@Repository
public interface ReactiveExpenseMongoRepository extends ReactiveMongoRepository<ExpenseEntity, String> {

    Flux<ExpenseEntity> findAllByEventIdOrderBySpentAtDesc(String eventId);

    Mono<ExpenseEntity> findByIdAndEventId(String id, String eventId);

    Mono<Long> deleteByIdAndEventId(String id, String eventId);

    Mono<Long> deleteAllByEventId(String eventId);
}
