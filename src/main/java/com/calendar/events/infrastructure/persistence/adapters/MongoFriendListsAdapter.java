package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.FriendList;
import com.calendar.events.domain.ports.FriendLists;
import com.calendar.events.infrastructure.persistence.mappers.FriendListPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendListMongoRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The {@code friend_lists} collection: one document per user, replaced by each new answer. */
@Component
public class MongoFriendListsAdapter implements FriendLists {

    private final ReactiveFriendListMongoRepository mongoRepository;
    private final FriendListPersistenceMapper mapper;

    public MongoFriendListsAdapter(ReactiveFriendListMongoRepository mongoRepository, FriendListPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> save(FriendList list) {
        return mongoRepository.save(mapper.toEntity(list)).then();
    }

    @Override
    public Mono<FriendList> findOf(String userId) {
        return mongoRepository.findById(userId).map(mapper::toDomain);
    }

    @Override
    public Flux<FriendList> findNaming(String userId) {
        return mongoRepository.findNaming(userId).map(mapper::toDomain);
    }

    @Override
    public Mono<Void> delete(String userId) {
        return mongoRepository.deleteById(userId);
    }
}
