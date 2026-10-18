package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.FriendshipEntity;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

@Repository
public interface ReactiveFriendshipMongoRepository extends ReactiveMongoRepository<FriendshipEntity, String> {

    /** The pairs where the user is either side and the two are friends. */
    @Query("{ 'friends': true, $or: [ { 'a': ?0 }, { 'b': ?0 } ] }")
    Flux<FriendshipEntity> findFriendshipsOf(String userId);

    /** Every pair currently friends. */
    @Query("{ 'friends': true }")
    Flux<FriendshipEntity> findAllFriends();

    /** Rewrites the pair only if the stored version is lower: 0 when it is absent, as new or newer. */
    @Query("{ '_id': ?0, 'version': { $lt: ?4 } }")
    @Update("{ '$set': { 'friends': ?1, 'closeness': ?2, 'since': ?3, 'version': ?4, 'streamedVersion': ?4 } }")
    Mono<Long> overwriteIfOlder(String id, boolean friends, String closeness, Instant since, long version);

    /** Notes the last version streamed for a stored pair; 0 when it is absent. */
    @Query("{ '_id': ?0 }")
    @Update("{ '$set': { 'streamedVersion': ?1 } }")
    Mono<Long> markStreamed(String id, long version);

    /** Ends the pair, version kept, only while it is still friends at that version: 0 otherwise. */
    @Query("{ '_id': ?0, 'friends': true, 'version': ?1 }")
    @Update("{ '$set': { 'friends': false, 'closeness': null, 'since': null } }")
    Mono<Long> endIfAt(String id, long version);

    @Query(value = "{ $or: [ { 'a': ?0 }, { 'b': ?0 } ] }", delete = true)
    Mono<Long> deleteInvolving(String userId);
}
