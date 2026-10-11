package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.infrastructure.persistence.mappers.EventPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;

@Component
public class MongoEventRepositoryAdapter implements EventRepository {

    private final ReactiveEventMongoRepository mongoRepository;
    private final EventPersistenceMapper mapper;

    public MongoEventRepositoryAdapter(ReactiveEventMongoRepository mongoRepository,
                                       EventPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Flux<Event> findAll() {
        return mongoRepository.findAll().map(mapper::toDomain);
    }

    @Override
    public Mono<Event> findById(String id) {
        return mongoRepository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Mono<Event> save(Event event) {
        return mongoRepository.save(mapper.toEntity(event)).map(mapper::toDomain);
    }

    @Override
    public Mono<Void> deleteById(String id) {
        return mongoRepository.deleteById(id);
    }

    @Override
    public Flux<Event> findByParticipantId(String userId) {
        return mongoRepository.findAllByParticipantIdsContains(userId).map(mapper::toDomain);
    }

    @Override
    public Flux<Event> findFeed(String userId, Collection<String> friendIds) {
        return mongoRepository.findFeed(userId, friendIds).map(mapper::toDomain);
    }

    @Override
    public Mono<Event> addParticipant(String eventId, String userId) {
        return mongoRepository.addParticipant(eventId, userId).then(findById(eventId));
    }

    @Override
    public Mono<Event> removeParticipant(String eventId, String userId) {
        return mongoRepository.removeParticipant(eventId, userId).then(findById(eventId));
    }

    @Override
    public Flux<Event> findOccupying(Collection<String> userIds, Instant from, Instant to) {
        return mongoRepository.findOccupying(userIds, from, to, from.minus(Event.DEFAULT_DURATION))
                .map(mapper::toDomain);
    }
}
