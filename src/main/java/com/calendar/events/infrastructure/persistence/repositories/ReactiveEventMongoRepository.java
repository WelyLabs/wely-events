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
import java.util.Map;

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
    // The same update marks the group for the relay: no window where the change exists unannounced.
    // Matching on the absent participant (or present one) keeps a repeated click from bumping the version.
    @Query("{ '_id': ?0, 'participantIds': { $ne: ?1 } }")
    @Update("{ '$addToSet': { 'participantIds': ?1 }, '$inc': { 'groupVersion': 1 }, '$set': { 'groupSynced': false } }")
    Mono<Long> addParticipant(String eventId, String userId);

    @Query("{ '_id': ?0, 'participantIds': ?1 }")
    @Update("{ '$pull': { 'participantIds': ?1 }, '$inc': { 'groupVersion': 1 }, '$set': { 'groupSynced': false } }")
    Mono<Long> removeParticipant(String eventId, String userId);

    /** Events whose member list wely-chat has not been sent yet, oldest first. Absent counts as not sent. */
    @Query(value = "{ 'groupSynced': { $in: [ false, null ] } }", sort = "{ '_id': 1 }")
    Flux<EventEntity> findGroupsToPublish(Limit limit);

    /**
     * Marks the group sent, unless it changed since it was read: the version no longer matches,
     * nothing is updated, and the relay sends the newer list on its next pass.
     */
    @Query("{ '_id': ?0, 'groupVersion': ?1 }")
    @Update("{ '$set': { 'groupSynced': true } }")
    Mono<Long> markGroupPublished(String eventId, Long groupVersion);

    /**
     * Events the users take part in or organise that overlap [from, to). An event without an end
     * overlaps if it starts after {@code startedAfter}, i.e. from minus its default duration.
     * An open poll only proposes dates: it keeps nobody busy.
     */
    @Query("""
            { $and: [
                { $or: [ { participantIds: { $in: ?0 } }, { organizerId: { $in: ?0 } } ] },
                { 'poll.status': { $ne: 'OPEN' } },
                { startDate: { $lt: ?2 } },
                { $or: [ { endDate: { $gt: ?1 } }, { endDate: null, startDate: { $gt: ?3 } } ] }
            ] }""")
    Flux<EventEntity> findOccupying(Collection<String> userIds, Instant from, Instant to, Instant startedAfter);

    // Filtered on the organizer too: a cover is only ever changed by whoever organizes the event.
    @Query("{ '_id': ?0, 'organizerId': ?1 }")
    @Update("{ '$set': { 'image': ?2 } }")
    Mono<Long> setImage(String eventId, String organizerId, String image);

    /** Every event the user organizes or takes part in; both fields are indexed. */
    @Query(value = "{ $or: [ { participantIds: ?0 }, { organizerId: ?0 } ] }", sort = "{ startDate: 1 }")
    Flux<EventEntity> findInvolving(String userId);

    /**
     * Not over yet: ends at or after {@code now}, or has no end and started at or after
     * {@code startedFrom}, i.e. now minus the default duration. Organized or joined by the user.
     */
    @Query(value = """
            { $and: [
                { $or: [ { organizerId: ?0 }, { participantIds: ?0 } ] },
                { $or: [ { endDate: { $gte: ?1 } }, { endDate: null, startDate: { $gte: ?2 } } ] }
            ] }""", sort = "{ startDate: 1 }")
    Flux<EventEntity> findUpcomingInvolving(String userId, Instant now, Instant startedFrom);

    /** Not over yet, as in {@link #findUpcomingInvolving}, and both users are on it. */
    @Query(value = """
            { $and: [
                { $or: [ { organizerId: ?0 }, { participantIds: ?0 } ] },
                { $or: [ { organizerId: ?1 }, { participantIds: ?1 } ] },
                { $or: [ { endDate: { $gte: ?2 } }, { endDate: null, startDate: { $gte: ?3 } } ] }
            ] }""", sort = "{ startDate: 1 }")
    Flux<EventEntity> findUpcomingInvolvingBoth(String userId, String otherId, Instant now, Instant startedFrom,
                                                Limit limit);

    /** Over: the exact complement of {@link #findUpcomingInvolvingBoth}'s date clause. Most recent first. */
    @Query(value = """
            { $and: [
                { $or: [ { organizerId: ?0 }, { participantIds: ?0 } ] },
                { $or: [ { organizerId: ?1 }, { participantIds: ?1 } ] },
                { $or: [ { endDate: { $lt: ?2 } }, { endDate: null, startDate: { $lt: ?3 } } ] }
            ] }""", sort = "{ startDate: -1 }")
    Flux<EventEntity> findPastInvolvingBoth(String userId, String otherId, Instant now, Instant startedBefore,
                                            Limit limit);

    @Query(value = """
            { $and: [
                { $or: [ { organizerId: ?0 }, { participantIds: ?0 } ] },
                { $or: [ { organizerId: ?1 }, { participantIds: ?1 } ] },
                { $or: [ { endDate: { $lt: ?2 } }, { endDate: null, startDate: { $lt: ?3 } } ] }
            ] }""", count = true)
    Mono<Long> countPastInvolvingBoth(String userId, String otherId, Instant now, Instant startedBefore);

    // The voter's id is a field name: one ballot is one $set, and two voters never touch the same field.
    @Query("{ '_id': ?0, 'poll.status': 'OPEN' }")
    @Update("{ '$set': { 'poll.ballots.?1': ?2 } }")
    Mono<Long> castBallot(String eventId, String voterId, Map<String, String> answers);

    @Query("{ '_id': ?0, 'poll.status': 'OPEN' }")
    @Update("{ '$unset': { 'poll.ballots.?1': '' } }")
    Mono<Long> withdrawBallot(String eventId, String voterId);

    /**
     * Compare-and-set on the open poll: only one fix can match. The same update moves the dates,
     * subscribes the yes voters, and flags both the group and the date announcement for their relays.
     */
    @Query("{ '_id': ?0, 'organizerId': ?1, 'poll.status': 'OPEN' }")
    @Update("""
            { '$set': { 'startDate': ?2, 'endDate': ?3, 'poll.status': 'CLOSED', 'poll.fixedSlotId': ?4,
                        'dateFixSynced': false, 'groupSynced': false },
              '$addToSet': { 'participantIds': { '$each': ?5 } },
              '$inc': { 'groupVersion': 1 } }""")
    Mono<Long> fixPollDate(String eventId, String organizerId, Instant start, Instant end, String slotId,
                           Collection<String> yesVoterIds);

    /** Open polls the user organizes, takes part in, or has a ballot in. */
    @Query(value = """
            { 'poll.status': 'OPEN',
              $or: [ { organizerId: ?0 }, { participantIds: ?0 }, { 'poll.ballots.?0': { $exists: true } } ] }""",
            sort = "{ startDate: 1 }")
    Flux<EventEntity> findOpenPollsInvolving(String userId);

    /** Fixed dates wely-chat has not been told about yet, oldest first. */
    @Query(value = "{ 'dateFixSynced': false }", sort = "{ '_id': 1 }")
    Flux<EventEntity> findDateFixesToPublish(Limit limit);

    // A poll closes once, so there is a single announcement per event: the flag alone is enough.
    @Query("{ '_id': ?0, 'dateFixSynced': false }")
    @Update("{ '$set': { 'dateFixSynced': true } }")
    Mono<Long> markDateFixPublished(String eventId);
}
