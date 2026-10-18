package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.ReunionSnoozeEntity;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Repository
public interface ReactiveReunionSnoozeMongoRepository extends ReactiveMongoRepository<ReunionSnoozeEntity, String> {

    /** Still running: the TTL monitor passes about once a minute, so an expired one may linger a little. */
    @Query("{ 'userId': ?0, 'until': { $gt: ?1 } }")
    Flux<ReunionSnoozeEntity> findActiveBy(String userId, Instant now);

    @Query(value = "{ $or: [ { 'userId': ?0 }, { 'friendId': ?0 } ] }", delete = true)
    Mono<Long> deleteInvolving(String userId);
}
