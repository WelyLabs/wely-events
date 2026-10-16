package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.DeletedUserMarker;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.persistence.entities.DeletedUserEntity;
import com.calendar.events.infrastructure.persistence.mappers.DeletedUserPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveDeletedUserMongoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/** The {@code deleted_users} collection, whose store failures become STORE_UNAVAILABLE. */
@Slf4j
@Component
public class MongoDeletedUserMarkersAdapter implements DeletedUserMarkers {

    private final ReactiveDeletedUserMongoRepository mongoRepository;
    private final DeletedUserPersistenceMapper mapper;

    public MongoDeletedUserMarkersAdapter(ReactiveDeletedUserMongoRepository mongoRepository,
                                          DeletedUserPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    /** An insert, not a save: a marker already there keeps its first deletion time. */
    @Override
    public Mono<DeletedUserMarker> markDeleted(String userId, Instant deletedAt) {
        return mongoRepository.insert(mapper.toEntity(new DeletedUserMarker(userId, deletedAt, null)))
                .onErrorResume(DuplicateKeyException.class, duplicate -> mongoRepository.findById(userId))
                .map(mapper::toDomain)
                .onErrorMap(DataAccessException.class, error -> unavailable("mark deleted", error));
    }

    @Override
    public Mono<Boolean> isDeleted(String userId) {
        return mongoRepository.existsById(userId)
                .onErrorMap(DataAccessException.class, error -> unavailable("read", error));
    }

    @Override
    public Mono<Set<String>> deletedAmong(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return mongoRepository.findDeletedAmong(userIds)
                .map(DeletedUserEntity::getId)
                .collect(Collectors.toUnmodifiableSet())
                .onErrorMap(DataAccessException.class, error -> unavailable("read", error));
    }

    @Override
    public Mono<Set<String>> erasedAmong(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return mongoRepository.findErasedAmong(userIds)
                .map(DeletedUserEntity::getId)
                .collect(Collectors.toUnmodifiableSet())
                .onErrorMap(DataAccessException.class, error -> unavailable("read", error));
    }

    @Override
    public Mono<Void> markErased(String userId, Instant erasedAt) {
        return mongoRepository.markErased(userId, erasedAt)
                .then()
                .onErrorMap(DataAccessException.class, error -> unavailable("mark erased", error));
    }

    private static EventException unavailable(String operation, DataAccessException error) {
        log.warn("Deleted-accounts register: {} failed: {}", operation, error.getClass().getSimpleName());
        return new EventException(EventErrorCode.STORE_UNAVAILABLE);
    }
}
