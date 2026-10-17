package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.BirthdayNoticeEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface ReactiveBirthdayNoticeMongoRepository extends ReactiveMongoRepository<BirthdayNoticeEntity, String> {

    @Query(value = "{ 'published': false }", sort = "{ 'recordedAt': 1 }")
    Flux<BirthdayNoticeEntity> findPending(Limit limit);

    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'published': true } }")
    Mono<Long> markPublished(String key);

    /** Not sent yet, and for or about the user. */
    @Query(value = "{ 'published': false, $or: [ { 'recipientId': ?0 }, { 'honoreeId': ?0 } ] }", delete = true)
    Mono<Long> deletePendingConcerning(String userId);
}
