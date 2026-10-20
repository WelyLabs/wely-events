package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.FriendshipNoticeType;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipNoticePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipNoticeMongoRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/** The {@code friendship_notices} collection: an insert on the notice's key, which a second insert hits. */
@Component
public class MongoFriendshipNoticeOutboxAdapter implements FriendshipNoticeOutbox {

    private final ReactiveFriendshipNoticeMongoRepository mongoRepository;
    private final FriendshipNoticePersistenceMapper mapper;
    private final Clock clock;

    public MongoFriendshipNoticeOutboxAdapter(ReactiveFriendshipNoticeMongoRepository mongoRepository,
                                              FriendshipNoticePersistenceMapper mapper, Clock clock) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> record(FriendshipNotice notice) {
        return Mono.defer(() -> mongoRepository.insert(mapper.toEntity(notice, clock.instant())))
                .thenReturn(true)
                .onErrorResume(DuplicateKeyException.class, alreadyRecorded -> Mono.just(false));
    }

    @Override
    public Flux<FriendshipNotice> recordedFor(String recipientId, FriendshipNoticeType type, Instant since) {
        return mongoRepository.findRecordedFor(recipientId, type.name(), since).map(mapper::toDomain);
    }

    @Override
    public Flux<FriendshipNotice> findPending(int limit) {
        return mongoRepository.findPending(Limit.of(limit)).map(mapper::toDomain);
    }

    @Override
    public Mono<Long> countPending() {
        return mongoRepository.countPending();
    }

    @Override
    public Mono<Void> markPublished(String key) {
        return mongoRepository.markPublished(key).then();
    }

    @Override
    public Mono<Void> forget(String userId) {
        return mongoRepository.deletePendingConcerning(userId).then();
    }
}
