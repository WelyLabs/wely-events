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

    // Never the organizer: leaving the organizer's seat empty is a hand-over or a deletion.
    @Query("{ '_id': ?0, 'participantIds': ?1, 'organizerId': { $ne: ?1 } }")
    @Update("{ '$pull': { 'participantIds': ?1 }, '$inc': { 'groupVersion': 1 }, '$set': { 'groupSynced': false } }")
    Mono<Long> removeParticipant(String eventId, String userId);

    /** One write: the successor must still take part and the organizer still organize, or nothing changes. */
    @Query("{ '_id': ?0, 'organizerId': ?1, 'participantIds': ?2 }")
    @Update("""
            { '$set': { 'organizerId': ?2, 'groupSynced': false },
              '$pull': { 'participantIds': ?1 },
              '$inc': { 'groupVersion': 1 } }""")
    Mono<Long> handOver(String eventId, String organizerId, String newOrganizerId);

    /** Removes the event and returns it as it was, its group version included. */
    @Query(value = "{ '_id': ?0, 'organizerId': ?1 }", delete = true)
    Mono<EventEntity> deleteByOrganizer(String eventId, String organizerId);

    /** As {@link #deleteByOrganizer}, only while nobody but the organizer takes part. */
    @Query(value = """
            { '_id': ?0, 'organizerId': ?1,
              $or: [ { 'participantIds': { $size: 0 } }, { 'participantIds': [ ?1 ] } ] }""", delete = true)
    Mono<EventEntity> deleteIfAlone(String eventId, String organizerId);

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

    /** A community's events not over yet, as {@link #findOccupying} counts it: without an end, one hour. */
    @Query(value = """
            { communityId: ?0,
              $or: [ { endDate: { $gt: ?1 } }, { endDate: null, startDate: { $gt: ?2 } } ] }""",
            sort = "{ startDate: 1 }")
    Flux<EventEntity> findUpcomingInCommunity(String communityId, Instant endingAfter, Instant startedAfter);

    // A YES is taking part: the answer field goes, the participant comes, and the group follows in
    // the same update, as for a subscription. Matching on the absent participant keeps a repeated
    // YES from bumping the version.
    @Query("{ '_id': ?0, 'communityId': { $ne: null }, 'participantIds': { $ne: ?1 } }")
    @Update("{ '$unset': { 'responses.?1': '' }, '$addToSet': { 'participantIds': ?1 }, '$inc': { 'groupVersion': 1 }, '$set': { 'groupSynced': false } }")
    Mono<Long> respondYes(String eventId, String userId);

    // MAYBE or NO from someone taking part: out of the participants, answer kept, group told.
    @Query("{ '_id': ?0, 'communityId': { $ne: null }, 'participantIds': ?1, 'organizerId': { $ne: ?1 } }")
    @Update("{ '$set': { 'responses.?1': ?2, 'groupSynced': false }, '$pull': { 'participantIds': ?1 }, '$inc': { 'groupVersion': 1 } }")
    Mono<Long> respondAndLeave(String eventId, String userId, String answer);

    // MAYBE or NO from someone not taking part: only the answer changes, the group has nothing to hear.
    @Query("{ '_id': ?0, 'communityId': { $ne: null }, 'participantIds': { $ne: ?1 } }")
    @Update("{ '$set': { 'responses.?1': ?2 } }")
    Mono<Long> respondFromOutside(String eventId, String userId, String answer);

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
                        'dateFixSynced': false, 'groupSynced': false,
                        'rescheduledFromStart': null, 'rescheduledFromEnd': null },
              '$addToSet': { 'participantIds': { '$each': ?5 } },
              '$inc': { 'groupVersion': 1, 'dateFixVersion': 1 } }""")
    Mono<Long> fixPollDate(String eventId, String organizerId, Instant start, Instant end, String slotId,
                           Collection<String> yesVoterIds);

    /** Open polls the user organizes, takes part in, or has a ballot in. */
    @Query(value = """
            { 'poll.status': 'OPEN',
              $or: [ { organizerId: ?0 }, { participantIds: ?0 }, { 'poll.ballots.?0': { $exists: true } } ] }""",
            sort = "{ startDate: 1 }")
    Flux<EventEntity> findOpenPollsInvolving(String userId);

    /**
     * Compare-and-set on the start that was read: two moves at once cannot both land, and an open
     * poll is never moved. The same update keeps the previous dates and sets the date relay's flag:
     * false to announce the move, null when nobody else takes part and there is no one to tell.
     */
    @Query("{ '_id': ?0, 'organizerId': ?1, 'startDate': ?2, 'poll.status': { $ne: 'OPEN' } }")
    @Update("""
            { '$set': { 'startDate': ?3, 'endDate': ?4, 'rescheduledFromStart': ?2, 'rescheduledFromEnd': ?5,
                        'dateFixSynced': ?6 },
              '$inc': { 'dateFixVersion': 1 } }""")
    Mono<Long> reschedule(String eventId, String organizerId, Instant expectedStart, Instant start, Instant end,
                          Instant previousEnd, Boolean dateFixSynced);

    /** Fixed or moved dates wely-chat has not been told about yet, oldest first. */
    @Query(value = "{ 'dateFixSynced': false }", sort = "{ '_id': 1 }")
    Flux<EventEntity> findDateFixesToPublish(Limit limit);

    /**
     * Marks the date announced, unless it moved again since it was read: the version no longer
     * matches, nothing is updated, and the relay announces the newer date on its next pass.
     */
    @Query("{ '_id': ?0, 'dateFixSynced': false, 'dateFixVersion': ?1 }")
    @Update("{ '$set': { 'dateFixSynced': true } }")
    Mono<Long> markDateFixPublished(String eventId, Long dateFixVersion);
}
