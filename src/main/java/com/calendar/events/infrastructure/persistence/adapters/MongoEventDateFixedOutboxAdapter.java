package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The events collection seen as the date-fixed outbox: fixes left to announce, and marking them sent. */
@Component
public class MongoEventDateFixedOutboxAdapter {

    private final ReactiveEventMongoRepository mongoRepository;

    public MongoEventDateFixedOutboxAdapter(ReactiveEventMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    public Flux<EventEntity> findPending(int batchSize) {
        return mongoRepository.findDateFixesToPublish(Limit.of(batchSize));
    }

    /** @return whether it was marked; false if another pass marked it first. */
    public Mono<Boolean> markPublished(String eventId) {
        return mongoRepository.markDateFixPublished(eventId).map(updated -> updated > 0);
    }
}
