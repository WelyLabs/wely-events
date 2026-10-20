package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.infrastructure.persistence.entities.EventDeletionEntity;
import com.calendar.events.infrastructure.persistence.mappers.EventDeletionPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventDeletionMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoEventDeletionOutboxAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Mock
    private ReactiveEventDeletionMongoRepository mongoRepository;

    private MongoEventDeletionOutboxAdapter outbox;

    @BeforeEach
    void setUp() {
        outbox = new MongoEventDeletionOutboxAdapter(mongoRepository, Mappers.getMapper(EventDeletionPersistenceMapper.class));
    }

    @Test
    void record_shouldStoreTheTombstoneUnderTheEventId() {
        when(mongoRepository.save(argThat(entity -> "e1".equals(entity.getId()) && entity.getVersion() == 4L
                && "Quiz".equals(entity.getTitle()) && NOW.equals(entity.getDeletedAt()))))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(outbox.record(new EventDeletion("e1", "Quiz", 4L, NOW))).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldPassOnAFailure() {
        when(mongoRepository.save(argThat(entity -> "e1".equals(entity.getId()))))
                .thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(outbox.record(new EventDeletion("e1", "Quiz", 4L, NOW)))
                .expectError(IllegalStateException.class).verify(Duration.ofSeconds(10));
    }

    @Test
    void findPending_shouldAskForOneBatch() {
        when(mongoRepository.findPending(Limit.of(10)))
                .thenReturn(Flux.just(new EventDeletionEntity("e1", "Quiz", 4L, NOW)));

        StepVerifier.create(outbox.findPending(10))
                .expectNext(new EventDeletion("e1", "Quiz", 4L, NOW))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldRemoveTheTombstone() {
        when(mongoRepository.deleteTombstone("e1")).thenReturn(Mono.just(1L));

        StepVerifier.create(outbox.markPublished("e1")).expectComplete().verify(Duration.ofSeconds(10));

        verify(mongoRepository).deleteTombstone("e1");
    }

    @Test
    void countPending_shouldCountWhatIsLeftToSend_onTheServer() {
        when(mongoRepository.count()).thenReturn(Mono.just(7L));

        StepVerifier.create(outbox.countPending()).expectNext(7L).expectComplete().verify(Duration.ofSeconds(10));
    }
}
