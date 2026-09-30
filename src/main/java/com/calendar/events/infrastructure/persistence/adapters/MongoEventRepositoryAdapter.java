package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.infrastructure.persistence.mappers.EventPersistenceMapper;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MongoEventRepositoryAdapter implements EventRepository {

    private final ReactiveEventMongoRepository mongoRepository;
    private final EventPersistenceMapper mapper;
    private final ReactiveMongoTemplate mongoTemplate;

    public MongoEventRepositoryAdapter(ReactiveEventMongoRepository mongoRepository,
                                       EventPersistenceMapper mapper,
                                       ReactiveMongoTemplate mongoTemplate) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
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
    public Flux<Event> findByParticipantIdNot(String userId) {
        return mongoRepository.findAllByParticipantIdsNotContains(userId).map(mapper::toDomain);
    }

    @Override
    public Mono<Event> addParticipant(String eventId, String userId) {
        // $addToSet is idempotent and touches only the array, so a concurrent
        // subscription from another user cannot be overwritten.
        return applyToParticipants(eventId, new Update().addToSet("participantIds", userId));
    }

    @Override
    public Mono<Event> removeParticipant(String eventId, String userId) {
        return applyToParticipants(eventId, new Update().pull("participantIds", userId));
    }

    private Mono<Event> applyToParticipants(String eventId, Update update) {
        return mongoTemplate.findAndModify(
                        Query.query(Criteria.where("_id").is(eventId)),
                        update,
                        FindAndModifyOptions.options().returnNew(true),
                        EventEntity.class)
                .map(mapper::toDomain);
    }
}
