package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
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
}
