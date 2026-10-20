package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The events collection seen as the date-fixed outbox: fixes and moves left to announce, and marking them sent. */
@Component
public class MongoEventDateFixedOutboxAdapter {

    private final ReactiveEventMongoRepository mongoRepository;

    public MongoEventDateFixedOutboxAdapter(ReactiveEventMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    public Flux<EventEntity> findPending(int batchSize) {
        return mongoRepository.findDateFixesToPublish(Limit.of(batchSize));
    }

    /** How many fixed dates are left to announce, for the backlog gauge. */
    public Mono<Long> countPending() {
        return mongoRepository.countDateFixesToPublish();
    }

    /** @return whether it was marked; false if another pass marked it first, or the date moved since. */
    public Mono<Boolean> markPublished(String eventId, Long dateFixVersion) {
        return mongoRepository.markDateFixPublished(eventId, dateFixVersion).map(updated -> updated > 0);
    }
}
