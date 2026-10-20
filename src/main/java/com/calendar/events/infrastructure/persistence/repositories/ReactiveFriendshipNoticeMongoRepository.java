package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.FriendshipNoticeEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Repository
public interface ReactiveFriendshipNoticeMongoRepository extends ReactiveMongoRepository<FriendshipNoticeEntity, String> {

    @Query(value = "{ 'published': false }", sort = "{ 'recordedAt': 1 }")
    Flux<FriendshipNoticeEntity> findPending(Limit limit);

    /** The same filter as {@link #findPending}, counted on the server. */
    @Query(value = "{ 'published': false }", count = true)
    Mono<Long> countPending();

    /** On the recipient_type_recorded index: the nudge caps' one read per user. */
    @Query(value = "{ 'recipientId': ?0, 'type': ?1, 'recordedAt': { $gte: ?2 } }", sort = "{ 'recordedAt': -1 }")
    Flux<FriendshipNoticeEntity> findRecordedFor(String recipientId, String type, Instant since);

    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'published': true } }")
    Mono<Long> markPublished(String key);

    /** Not sent yet, and for or about the user. */
    @Query(value = "{ 'published': false, $or: [ { 'recipientId': ?0 }, { 'friendId': ?0 } ] }", delete = true)
    Mono<Long> deletePendingConcerning(String userId);
}
