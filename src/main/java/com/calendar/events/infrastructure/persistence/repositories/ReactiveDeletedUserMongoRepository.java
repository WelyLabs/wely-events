package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.DeletedUserEntity;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;

@Repository
public interface ReactiveDeletedUserMongoRepository extends ReactiveMongoRepository<DeletedUserEntity, String> {

    /** The markers among these ids, by _id in one query; only the id is read. */
    @Query(value = "{ '_id': { $in: ?0 } }", fields = "{ '_id': 1 }")
    Flux<DeletedUserEntity> findDeletedAmong(Collection<String> userIds);

    // $max keeps the latest erasure time, whichever run finishes last.
    @Query("{ '_id': ?0 }")
    @Update("{ '$max': { 'erasedAt': ?1 } }")
    Mono<Long> markErased(String userId, Instant erasedAt);
}
