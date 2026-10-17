package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.FriendListEntity;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ReactiveFriendListMongoRepository extends ReactiveMongoRepository<FriendListEntity, String> {

    /** Every other user's list with this user among the friends. */
    @Query("{ 'friends.userId': ?0, '_id': { $ne: ?0 } }")
    Flux<FriendListEntity> findNaming(String userId);
}
