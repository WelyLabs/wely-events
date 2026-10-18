package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.FriendshipHistoryEntity;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Repository
public interface ReactiveFriendshipHistoryMongoRepository extends ReactiveMongoRepository<FriendshipHistoryEntity, String> {

    @Query(value = "{ $or: [ { 'a': ?0 }, { 'b': ?0 } ], 'at': { $gte: ?1 } }", sort = "{ 'at': 1, 'version': 1 }")
    Flux<FriendshipHistoryEntity> findInvolvingSince(String userId, Instant from);

    @Query(value = "{ $or: [ { 'a': ?0 }, { 'b': ?0 } ] }", delete = true)
    Mono<Long> deleteInvolving(String userId);
}
