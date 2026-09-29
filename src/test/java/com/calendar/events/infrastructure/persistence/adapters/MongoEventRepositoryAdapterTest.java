package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.mappers.EventPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.bson.Document;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoEventRepositoryAdapterTest {

    @Mock
    private ReactiveEventMongoRepository mongoRepository;

    @Mock
    private EventPersistenceMapper mapper;

    @Mock
    private ReactiveMongoTemplate mongoTemplate;

    private MongoEventRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoEventRepositoryAdapter(mongoRepository, mapper, mongoTemplate);
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
    void findByParticipantIdNot_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findAllByParticipantIdsNotContains("user1")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Flux<Event> result = adapter.findByParticipantIdNot("user1");

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void addParticipant_shouldUseAddToSetAndReturnUpdatedEvent() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(EventEntity.class)))
                .thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.addParticipant("1", "user123"))
                .expectNext(domain)
                .verifyComplete();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        ArgumentCaptor<FindAndModifyOptions> options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongoTemplate).findAndModify(any(Query.class), update.capture(),
                options.capture(), eq(EventEntity.class));

        Document set = update.getValue().getUpdateObject();
        assertThat(set).containsKey("$addToSet");
        assertThat(options.getValue().isReturnNew()).isTrue();

        // Pas de save : une réécriture complète perdrait une inscription concurrente.
        verify(mongoRepository, never()).save(any());
    }

    @Test
    void removeParticipant_shouldUsePull() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(EventEntity.class)))
                .thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.removeParticipant("1", "user123"))
                .expectNext(domain)
                .verifyComplete();

        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(any(Query.class), update.capture(),
                any(FindAndModifyOptions.class), eq(EventEntity.class));
        assertThat(update.getValue().getUpdateObject()).containsKey("$pull");

        verify(mongoRepository, never()).save(any());
    }
}
