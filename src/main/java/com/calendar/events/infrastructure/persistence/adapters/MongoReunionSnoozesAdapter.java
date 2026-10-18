package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.ports.ReunionSnoozes;
import com.calendar.events.infrastructure.persistence.entities.ReunionSnoozeEntity;
import com.calendar.events.infrastructure.persistence.mappers.ReunionSnoozePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveReunionSnoozeMongoRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/** The {@code reunion_snoozes} collection: one document per pair and direction, expiring with its snooze. */
@Component
public class MongoReunionSnoozesAdapter implements ReunionSnoozes {

    private final ReactiveReunionSnoozeMongoRepository mongoRepository;
    private final ReunionSnoozePersistenceMapper mapper;

    public MongoReunionSnoozesAdapter(ReactiveReunionSnoozeMongoRepository mongoRepository,
                                      ReunionSnoozePersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> snooze(String userId, String friendId, Instant until) {
        return mongoRepository.save(mapper.toEntity(userId, friendId, until)).then();
    }

    @Override
    public Mono<Set<String>> snoozedBy(String userId, Instant now) {
        return mongoRepository.findActiveBy(userId, now)
                .map(ReunionSnoozeEntity::getFriendId)
                .collect(Collectors.toSet());
    }

    @Override
    public Mono<Void> forget(String userId) {
        return mongoRepository.deleteInvolving(userId).then();
    }
}
