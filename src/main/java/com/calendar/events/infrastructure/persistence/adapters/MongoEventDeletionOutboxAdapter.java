package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.infrastructure.persistence.mappers.EventDeletionPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventDeletionMongoRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The {@code event_deletions} collection: one tombstone per deleted event, removed once sent. */
@Component
public class MongoEventDeletionOutboxAdapter implements EventDeletionOutbox {

    private final ReactiveEventDeletionMongoRepository mongoRepository;
    private final EventDeletionPersistenceMapper mapper;

    public MongoEventDeletionOutboxAdapter(ReactiveEventDeletionMongoRepository mongoRepository,
                                           EventDeletionPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> record(EventDeletion deletion) {
        return mongoRepository.save(mapper.toEntity(deletion)).then();
    }

    @Override
    public Flux<EventDeletion> findPending(int limit) {
        return mongoRepository.findPending(Limit.of(limit)).map(mapper::toDomain);
    }

    @Override
    public Mono<Void> markPublished(String eventId) {
        return mongoRepository.deleteTombstone(eventId).then();
    }
}
