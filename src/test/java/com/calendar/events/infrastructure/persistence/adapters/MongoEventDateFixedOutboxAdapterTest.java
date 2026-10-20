package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoEventDateFixedOutboxAdapterTest {

    @Mock
    private ReactiveEventMongoRepository mongoRepository;

    @InjectMocks
    private MongoEventDateFixedOutboxAdapter outbox;

    @Test
    void findPending_shouldAskForOneBatch() {
        EventEntity event = new EventEntity();
        when(mongoRepository.findDateFixesToPublish(Limit.of(10))).thenReturn(Flux.just(event));

        StepVerifier.create(outbox.findPending(10)).expectNext(event).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldSayYes_whenItWasStillPending() {
        when(mongoRepository.markDateFixPublished("e1", 2L)).thenReturn(Mono.just(1L));

        StepVerifier.create(outbox.markPublished("e1", 2L)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldSayNo_whenAlreadyMarkedOrMovedSince() {
        when(mongoRepository.markDateFixPublished("e1", 2L)).thenReturn(Mono.just(0L));

        StepVerifier.create(outbox.markPublished("e1", 2L)).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
    }
}
