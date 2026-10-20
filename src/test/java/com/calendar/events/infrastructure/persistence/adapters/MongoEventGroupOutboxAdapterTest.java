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
class MongoEventGroupOutboxAdapterTest {

    @Mock
    private ReactiveEventMongoRepository mongoRepository;

    @InjectMocks
    private MongoEventGroupOutboxAdapter outbox;

    @Test
    void findPending_shouldAskForOneBatch() {
        EventEntity event = new EventEntity();
        when(mongoRepository.findGroupsToPublish(Limit.of(10))).thenReturn(Flux.just(event));

        StepVerifier.create(outbox.findPending(10)).expectNext(event).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldSayYes_whenTheVersionStillMatched() {
        when(mongoRepository.markGroupPublished("e1", 3L)).thenReturn(Mono.just(1L));

        StepVerifier.create(outbox.markPublished("e1", 3L)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldSayNo_whenTheGroupChangedMeanwhile() {
        when(mongoRepository.markGroupPublished("e1", 3L)).thenReturn(Mono.just(0L));

        StepVerifier.create(outbox.markPublished("e1", 3L)).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
    }
}
