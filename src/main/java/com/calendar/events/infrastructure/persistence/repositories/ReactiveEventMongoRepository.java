package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Collection;

@Repository
public interface ReactiveEventMongoRepository extends ReactiveMongoRepository<EventEntity, String> {
    Flux<EventEntity> findAllByParticipantIdsContains(String userId);

    Flux<EventEntity> findAllByParticipantIdsNotContains(String userId);

    /**
     * Events the users take part in or organise that overlap [from, to). An event without an end
     * overlaps if it starts after {@code startedAfter}, i.e. from minus its default duration.
     */
    @Query("""
            { $and: [
                { $or: [ { participantIds: { $in: ?0 } }, { organizerId: { $in: ?0 } } ] },
                { startDate: { $lt: ?2 } },
                { $or: [ { endDate: { $gt: ?1 } }, { endDate: null, startDate: { $gt: ?3 } } ] }
            ] }""")
    Flux<EventEntity> findOccupying(Collection<String> userIds, Instant from, Instant to, Instant startedAfter);
}
