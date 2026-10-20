package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The events collection seen as the group outbox: what is left to send, and marking it sent. */
@Component
public class MongoEventGroupOutboxAdapter {

    private final ReactiveEventMongoRepository mongoRepository;

    public MongoEventGroupOutboxAdapter(ReactiveEventMongoRepository mongoRepository) {
        this.mongoRepository = mongoRepository;
    }

    public Flux<EventEntity> findPending(int batchSize) {
        return mongoRepository.findGroupsToPublish(Limit.of(batchSize));
    }

    /** How many groups are left to send, for the backlog gauge. */
    public Mono<Long> countPending() {
        return mongoRepository.countGroupsToPublish();
    }

    /** @return whether it was marked; false if the group changed since it was read. */
    public Mono<Boolean> markPublished(String eventId, Long groupVersion) {
        return mongoRepository.markGroupPublished(eventId, groupVersion).map(updated -> updated > 0);
    }
}
