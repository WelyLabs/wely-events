package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;

@Repository
public interface ReactiveEventMongoRepository extends ReactiveMongoRepository<EventEntity, String> {
    Flux<EventEntity> findAllByParticipantIdsContains(String userId);

    /**
     * The feed: not taken part in, and either mine, public, or shared with friends by one of
     * mine. A missing visibility counts as FRIENDS, which {@code $in} with null matches.
     */
    @Query("""
            { $and: [
                { participantIds: { $ne: ?0 } },
                { $or: [
                    { organizerId: ?0 },
                    { visibility: 'PUBLIC' },
                    { organizerId: { $in: ?1 }, visibility: { $in: [ 'FRIENDS', null ] } }
                ] }
            ] }""")
    Flux<EventEntity> findFeed(String userId, Collection<String> friendIds);

    /**
     * The map: inside the box (a GeoJSON polygon, longitude first), not over yet, and visible to
     * the user. An event without an end is over once {@code startedAfter} passes it, as in
     * {@link #findOccupying}.
     */
    @Query(value = """
            { $and: [
                { coordinates: { $geoWithin: { $geometry: { type: 'Polygon', coordinates:
                    [ [ [ ?3, ?2 ], [ ?5, ?2 ], [ ?5, ?4 ], [ ?3, ?4 ], [ ?3, ?2 ] ] ] } } } },
                { $or: [ { endDate: { $gt: ?6 } }, { endDate: null, startDate: { $gt: ?7 } } ] },
                { $or: [
                    { organizerId: ?0 },
                    { participantIds: ?0 },
                    { visibility: 'PUBLIC' },
                    { organizerId: { $in: ?1 }, visibility: { $in: [ 'FRIENDS', null ] } }
                ] }
            ] }""", sort = "{ startDate: 1 }")
    Flux<EventEntity> findOnMap(String userId, Collection<String> friendIds,
                                double south, double west, double north, double east,
                                Instant endingAfter, Instant startedAfter, Limit limit);

    // $addToSet and $pull touch only the array, so concurrent subscriptions cannot overwrite each other.
    @Query("{ '_id': ?0 }")
    @Update("{ '$addToSet': { 'participantIds': ?1 } }")
    Mono<Long> addParticipant(String eventId, String userId);

    @Query("{ '_id': ?0 }")
    @Update("{ '$pull': { 'participantIds': ?1 } }")
    Mono<Long> removeParticipant(String eventId, String userId);

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
