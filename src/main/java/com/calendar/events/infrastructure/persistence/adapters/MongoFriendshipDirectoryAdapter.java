package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Friend;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.infrastructure.persistence.entities.FriendshipEntity;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipMongoRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The {@code friendships} collection, one document per pair. A write is a conditional overwrite on the
 * version, then an insert when the pair is new: the insert hitting an existing pair means it is stale.
 */
@Component
public class MongoFriendshipDirectoryAdapter implements FriendshipDirectory {

    private final ReactiveFriendshipMongoRepository mongoRepository;
    private final FriendshipPersistenceMapper mapper;

    public MongoFriendshipDirectoryAdapter(ReactiveFriendshipMongoRepository mongoRepository,
                                           FriendshipPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Flux<Friend> friendsOf(String userId) {
        return mongoRepository.findFriendshipsOf(userId)
                .map(mapper::toDomain)
                .map(friendship -> friendship.friendOf(userId));
    }

    @Override
    public Flux<Friendship> friendshipsOf(String userId) {
        return mongoRepository.findFriendshipsOf(userId).map(mapper::toDomain);
    }

    @Override
    public Flux<Friendship> allFriendships() {
        return mongoRepository.findAllFriends().map(mapper::toDomain);
    }

    @Override
    public Mono<Friendship> pairOf(String userId, String otherId) {
        if (userId.equals(otherId)) {
            return Mono.empty();
        }
        return mongoRepository.findById(Friendship.pairIdOf(userId, otherId)).map(mapper::toDomain);
    }

    @Override
    public Mono<Boolean> record(Friendship friendship) {
        FriendshipEntity entity = mapper.toEntity(friendship);
        return overwriteIfOlder(entity)
                .flatMap(overwritten -> overwritten ? Mono.just(true) : insert(entity));
    }

    @Override
    public Mono<Long> forget(String userId) {
        return mongoRepository.deleteInvolving(userId);
    }

    // A duplicate is a pair already as new or newer, or one inserted meanwhile, which the overwrite settles.
    private Mono<Boolean> insert(FriendshipEntity entity) {
        return Mono.defer(() -> mongoRepository.insert(entity))
                .thenReturn(true)
                .onErrorResume(DuplicateKeyException.class, present -> overwriteIfOlder(entity));
    }

    private Mono<Boolean> overwriteIfOlder(FriendshipEntity entity) {
        return Mono.defer(() -> mongoRepository.overwriteIfOlder(entity.getId(), entity.isFriends(),
                        entity.getCloseness(), entity.getSince(), entity.getVersion()))
                .map(overwritten -> overwritten > 0);
    }
}
