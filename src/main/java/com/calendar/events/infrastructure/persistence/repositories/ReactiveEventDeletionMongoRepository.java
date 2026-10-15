package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.EventDeletionEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ReactiveEventDeletionMongoRepository extends ReactiveMongoRepository<EventDeletionEntity, String> {

    /** Every tombstone is pending until sent: oldest deletion first. */
    @Query(value = "{}", sort = "{ 'deletedAt': 1 }")
    Flux<EventDeletionEntity> findPending(Limit limit);

    @Query(value = "{ '_id': ?0 }", delete = true)
    Mono<Long> deleteTombstone(String eventId);
}
