package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.mappers.EventPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.query.Update;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoEventRepositoryAdapterTest {

    @Mock
    private ReactiveEventMongoRepository mongoRepository;

    @Mock
    private EventPersistenceMapper mapper;

    private MongoEventRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoEventRepositoryAdapter(mongoRepository, mapper);
    }

    @Test
    void findAll_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findAll()).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Flux<Event> result = adapter.findAll();

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findById_shouldReturnMappedEvent() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findById("1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Mono<Event> result = adapter.findById("1");

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void save_shouldMapAndSave() {
        Event domainInput = Event.builder().title("Title").build();
        EventEntity entityInput = new EventEntity();
        EventEntity entitySaved = new EventEntity();
        Event domainOutput = Event.builder().id("1").title("Title").build();

        when(mapper.toEntity(domainInput)).thenReturn(entityInput);
        when(mongoRepository.save(entityInput)).thenReturn(Mono.just(entitySaved));
        when(mapper.toDomain(entitySaved)).thenReturn(domainOutput);

        Mono<Event> result = adapter.save(domainInput);

        StepVerifier.create(result)
                .expectNext(domainOutput)
                .verifyComplete();
    }

    @Test
    void deleteById_shouldCallRepository() {
        when(mongoRepository.deleteById("1")).thenReturn(Mono.empty());

        Mono<Void> result = adapter.deleteById("1");

        StepVerifier.create(result)
                .verifyComplete();
    }

    @Test
    void findByParticipantId_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findAllByParticipantIdsContains("user1")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Flux<Event> result = adapter.findByParticipantId("user1");

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findOccupying_shouldLetEventsWithoutAnEndLastTheDefaultDuration() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();
        Instant from = Instant.parse("2026-10-16T08:00:00Z");
        Instant to = Instant.parse("2026-10-16T20:00:00Z");
        List<String> users = List.of("user1", "user2");

        when(mongoRepository.findOccupying(users, from, to, from.minus(Event.DEFAULT_DURATION)))
                .thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findOccupying(users, from, to))
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findFeed_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findFeed("user1", List.of("friend"))).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Flux<Event> result = adapter.findFeed("user1", List.of("friend"));

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void addParticipant_shouldUpdateInPlaceThenReturnTheEvent() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();

        when(mongoRepository.addParticipant("1", "user123")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.addParticipant("1", "user123"))
                .expectNext(domain)
                .verifyComplete();

        // No save: a full rewrite would lose a concurrent subscription.
        verify(mongoRepository, never()).save(any());
    }

    @Test
    void addParticipant_shouldReturnNothing_whenTheEventDoesNotExist() {
        when(mongoRepository.addParticipant("missing", "user123")).thenReturn(Mono.just(0L));
        when(mongoRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.addParticipant("missing", "user123"))
                .verifyComplete();
    }

    @Test
    void removeParticipant_shouldUpdateInPlaceThenReturnTheEvent() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();

        when(mongoRepository.removeParticipant("1", "user123")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.removeParticipant("1", "user123"))
                .expectNext(domain)
                .verifyComplete();

        verify(mongoRepository, never()).save(any());
    }

    @Test
    void findOnMap_shouldPassTheBoxAndEndAnEventWithoutOneAfterAnHour() {
        Instant now = Instant.parse("2026-10-11T16:00:00Z");
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().id("1").build();
        when(mongoRepository.findOnMap("u", List.of("f"), 45.70, 4.77, 45.81, 4.92,
                now, now.minus(Event.DEFAULT_DURATION), Limit.of(200))).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findOnMap("u", List.of("f"), new GeoBox(45.70, 4.77, 45.81, 4.92), now, 200))
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findInvolving_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();
        when(mongoRepository.findInvolving("user1")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findInvolving("user1")).expectNext(domain).verifyComplete();
    }

    @Test
    void setImage_shouldReadTheEventBack_whenTheOrganizersUpdateMatched() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().id("1").image("events/1/cover-a.jpg").build();
        when(mongoRepository.setImage("1", "org", "events/1/cover-a.jpg")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.setImage("1", "org", "events/1/cover-a.jpg")).expectNext(domain).verifyComplete();
    }

    @Test
    void setImage_shouldBeEmpty_whenNoEventOfThatOrganizerMatched() {
        when(mongoRepository.setImage("1", "someone-else", null)).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.setImage("1", "someone-else", null)).verifyComplete();

        verify(mongoRepository, never()).findById("1");
    }

    private static final PollSlot FRIDAY = new PollSlot("fri", Instant.parse("2026-10-17T17:00:00Z"), Instant.parse("2026-10-17T19:00:00Z"));

    private static Event withPoll(PollStatus status) {
        return Event.builder().id("1").poll(new Poll(status, List.of(FRIDAY), null, null)).build();
    }

    private void readBack(Event domain) {
        EventEntity entity = new EventEntity();
        when(mongoRepository.findById("1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);
    }

    @Test
    void castBallot_shouldStoreTheAnswersAsText_andReadTheEventBack() {
        Event after = withPoll(PollStatus.OPEN);
        when(mapper.toStoredAnswers(Map.of("fri", PollAnswer.YES))).thenReturn(Map.of("fri", "YES"));
        when(mongoRepository.castBallot("1", "lea", Map.of("fri", "YES"))).thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of("fri", PollAnswer.YES))).expectNext(after).verifyComplete();
    }

    @Test
    void castBallot_shouldWithdrawTheBallot_whenTheAnswersAreEmpty() {
        Event after = withPoll(PollStatus.OPEN);
        when(mongoRepository.withdrawBallot("1", "lea")).thenReturn(Mono.just(0L));
        readBack(after);

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of())).expectNext(after).verifyComplete();
        verify(mongoRepository, never()).castBallot(any(), any(), any());
    }

    @Test
    void castBallot_shouldCountAnUnchangedBallot_whileThePollIsStillOpen() {
        // Mongo reports modified documents: the same ballot twice modifies nothing, yet matched.
        Event after = withPoll(PollStatus.OPEN);
        when(mapper.toStoredAnswers(Map.of("fri", PollAnswer.YES))).thenReturn(Map.of("fri", "YES"));
        when(mongoRepository.castBallot("1", "lea", Map.of("fri", "YES"))).thenReturn(Mono.just(0L));
        readBack(after);

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of("fri", PollAnswer.YES))).expectNext(after).verifyComplete();
    }

    @Test
    void castBallot_shouldBeEmpty_whenNothingChangedAndThePollIsClosed() {
        when(mapper.toStoredAnswers(Map.of("fri", PollAnswer.YES))).thenReturn(Map.of("fri", "YES"));
        when(mongoRepository.castBallot("1", "lea", Map.of("fri", "YES"))).thenReturn(Mono.just(0L));
        readBack(withPoll(PollStatus.CLOSED));

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of("fri", PollAnswer.YES))).verifyComplete();
    }

    @Test
    void castBallot_shouldRefuseAVoterIdThatWouldChangeTheFieldPath() {
        StepVerifier.create(adapter.castBallot("1", "a.b", Map.of()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(adapter.castBallot("1", "$where", Map.of()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void fixPollDate_shouldPassTheSlotAndYesVoters_andReadTheEventBack() {
        Event after = withPoll(PollStatus.CLOSED);
        when(mongoRepository.fixPollDate("1", "org", FRIDAY.start(), FRIDAY.end(), "fri", List.of("lea")))
                .thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.fixPollDate("1", "org", FRIDAY, List.of("lea"))).expectNext(after).verifyComplete();
    }

    @Test
    void fixPollDate_shouldBeEmpty_whenNoOpenPollMatched() {
        when(mongoRepository.fixPollDate("1", "org", FRIDAY.start(), FRIDAY.end(), "fri", List.of())).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.fixPollDate("1", "org", FRIDAY, List.of())).verifyComplete();
        verify(mongoRepository, never()).findById("1");
    }

    @Test
    void findOpenPollsInvolving_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = withPoll(PollStatus.OPEN);
        when(mongoRepository.findOpenPollsInvolving("lea")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findOpenPollsInvolving("lea")).expectNext(domain).verifyComplete();
    }

    @Test
    void findOpenPollsInvolving_shouldRefuseAUserIdThatWouldChangeTheFieldPath() {
        StepVerifier.create(adapter.findOpenPollsInvolving("a.b")).expectError(IllegalArgumentException.class).verify();
    }
}
