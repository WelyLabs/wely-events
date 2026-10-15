package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.infrastructure.persistence.mappers.EventPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class MongoEventRepositoryAdapter implements EventRepository {

    /** A voter id becomes a field name: a dot or a dollar would change which field is written. */
    private static final Pattern FIELD_SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

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
    public Mono<Event> deleteByOrganizer(String eventId, String organizerId) {
        return mongoRepository.deleteByOrganizer(eventId, organizerId).map(mapper::toDomain);
    }

    @Override
    public Mono<Event> deleteIfAlone(String eventId, String organizerId) {
        return mongoRepository.deleteIfAlone(eventId, organizerId).map(mapper::toDomain);
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
    public Flux<Event> findOnMap(String userId, Collection<String> friendIds, GeoBox box, Instant endingAfter, int limit) {
        return mongoRepository.findOnMap(userId, friendIds, box.south(), box.west(), box.north(), box.east(),
                        endingAfter, endingAfter.minus(Event.DEFAULT_DURATION), Limit.of(limit))
                .map(mapper::toDomain);
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
    public Mono<Event> handOver(String eventId, String organizerId, String newOrganizerId) {
        return mongoRepository.handOver(eventId, organizerId, newOrganizerId)
                .filter(modified -> modified > 0)
                .flatMap(modified -> findById(eventId));
    }

    @Override
    public Flux<Event> findOccupying(Collection<String> userIds, Instant from, Instant to) {
        return mongoRepository.findOccupying(userIds, from, to, from.minus(Event.DEFAULT_DURATION))
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Event> setImage(String eventId, String organizerId, String image) {
        return mongoRepository.setImage(eventId, organizerId, image)
                .filter(matched -> matched > 0)
                .flatMap(matched -> findById(eventId));
    }

    @Override
    public Flux<Event> findInvolving(String userId) {
        return mongoRepository.findInvolving(userId).map(mapper::toDomain);
    }

    @Override
    public Flux<Event> findUpcomingInvolving(String userId, Instant now) {
        return mongoRepository.findUpcomingInvolving(userId, now, now.minus(Event.DEFAULT_DURATION))
                .map(mapper::toDomain);
    }

    @Override
    public Flux<Event> findUpcomingInvolvingBoth(String userId, String otherId, Instant now, int limit) {
        return mongoRepository.findUpcomingInvolvingBoth(userId, otherId, now, now.minus(Event.DEFAULT_DURATION),
                        Limit.of(limit))
                .map(mapper::toDomain);
    }

    @Override
    public Flux<Event> findPastInvolvingBoth(String userId, String otherId, Instant now, int limit) {
        return mongoRepository.findPastInvolvingBoth(userId, otherId, now, now.minus(Event.DEFAULT_DURATION),
                        Limit.of(limit))
                .map(mapper::toDomain);
    }

    @Override
    public Mono<Long> countPastInvolvingBoth(String userId, String otherId, Instant now) {
        return mongoRepository.countPastInvolvingBoth(userId, otherId, now, now.minus(Event.DEFAULT_DURATION));
    }

    /**
     * Mongo counts modified documents, not matched ones: casting the same ballot again modifies
     * nothing. A poll never reopens, so finding it still open after the write means it matched.
     */
    @Override
    public Mono<Event> castBallot(String eventId, String voterId, Map<String, PollAnswer> answers) {
        if (!FIELD_SAFE_ID.matcher(voterId).matches()) {
            return Mono.error(new IllegalArgumentException("Voter id is not usable as a field name"));
        }
        Mono<Long> write = answers.isEmpty()
                ? mongoRepository.withdrawBallot(eventId, voterId)
                : mongoRepository.castBallot(eventId, voterId, mapper.toStoredAnswers(answers));
        return write.flatMap(modified -> findById(eventId)
                .filter(event -> modified > 0 || event.isPolling()));
    }

    @Override
    public Mono<Event> fixPollDate(String eventId, String organizerId, PollSlot slot, Collection<String> yesVoterIds) {
        return mongoRepository.fixPollDate(eventId, organizerId, slot.start(), slot.end(), slot.id(),
                        List.copyOf(yesVoterIds))
                .filter(modified -> modified > 0)
                .flatMap(modified -> findById(eventId));
    }

    @Override
    public Mono<Event> reschedule(Event read, TimeRange range, boolean announce) {
        return mongoRepository.reschedule(read.getId(), read.getOrganizerId(), read.getStartDate(), range.start(),
                        range.end(), read.getEndDate(), announce ? Boolean.FALSE : null)
                .filter(modified -> modified > 0)
                .flatMap(modified -> findById(read.getId()));
    }

    @Override
    public Flux<Event> findUpcomingInCommunity(String communityId, Instant now) {
        return mongoRepository.findUpcomingInCommunity(communityId, now, now.minus(Event.DEFAULT_DURATION))
                .map(mapper::toDomain);
    }

    /**
     * Three conditional writes, of which at most one matches the event as it stands: the answer is
     * a field named after the member, so it is checked like a voter id. Nothing modified is fine
     * when the answer was already the one stored; the event is read back either way, and empty
     * only when it is gone or the organizer tried to step out.
     */
    @Override
    public Mono<Event> respond(String eventId, String userId, PollAnswer answer) {
        if (!FIELD_SAFE_ID.matcher(userId).matches()) {
            return Mono.error(new IllegalArgumentException("User id is not usable as a field name"));
        }
        Mono<Long> write = answer == PollAnswer.YES
                ? mongoRepository.respondYes(eventId, userId)
                : mongoRepository.respondAndLeave(eventId, userId, answer.name())
                        .flatMap(modified -> modified > 0
                                ? Mono.just(modified)
                                : mongoRepository.respondFromOutside(eventId, userId, answer.name()));
        return write.then(findById(eventId))
                .filter(event -> event.isCommunityEvent()
                        && (answer == PollAnswer.YES || !userId.equals(event.getOrganizerId())));
    }

    @Override
    public Flux<Event> findOpenPollsInvolving(String userId) {
        if (!FIELD_SAFE_ID.matcher(userId).matches()) {
            return Flux.error(new IllegalArgumentException("User id is not usable as a field name"));
        }
        return mongoRepository.findOpenPollsInvolving(userId).map(mapper::toDomain);
    }
}
