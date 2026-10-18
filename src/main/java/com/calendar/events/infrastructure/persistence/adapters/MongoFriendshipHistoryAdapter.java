package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.domain.ports.FriendshipHistory;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipHistoryPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipHistoryMongoRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/** The {@code friendship_history} collection: an insert on {@code pairId:version}, which a replay hits. */
@Component
public class MongoFriendshipHistoryAdapter implements FriendshipHistory {

    private final ReactiveFriendshipHistoryMongoRepository mongoRepository;
    private final FriendshipHistoryPersistenceMapper mapper;

    public MongoFriendshipHistoryAdapter(ReactiveFriendshipHistoryMongoRepository mongoRepository,
                                         FriendshipHistoryPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Mono<Boolean> append(FriendshipHistoryEntry entry) {
        return Mono.defer(() -> mongoRepository.insert(mapper.toEntity(entry)))
                .thenReturn(true)
                .onErrorResume(DuplicateKeyException.class, alreadyThere -> Mono.just(false));
    }

    @Override
    public Flux<FriendshipHistoryEntry> involvingSince(String userId, Instant from) {
        return mongoRepository.findInvolvingSince(userId, from).map(mapper::toDomain);
    }

    @Override
    public Mono<Long> forget(String userId) {
        return mongoRepository.deleteInvolving(userId);
    }
}
