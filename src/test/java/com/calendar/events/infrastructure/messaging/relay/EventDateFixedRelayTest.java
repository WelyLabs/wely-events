package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.messaging.adapters.KafkaEventDateFixedPublisher;
import com.calendar.events.infrastructure.messaging.mappers.EventDateFixedMapper;
import com.calendar.events.infrastructure.messaging.models.EventDateFixedDTO;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventDateFixedOutboxAdapter;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventDateFixedRelayTest {

    @Mock
    private MongoEventDateFixedOutboxAdapter outbox;
    @Mock
    private KafkaEventDateFixedPublisher publisher;
    @Mock
    private EventDateFixedMapper mapper;

    private EventDateFixedRelay relay;

    @BeforeEach
    void setUp() {
        relay = new EventDateFixedRelay(outbox, publisher, mapper, Duration.ofMillis(50), 10);
    }

    private EventDateFixedDTO pending(EventEntity event) {
        EventDateFixedDTO dto = new EventDateFixedDTO(event.getId(), "Dinner",
                Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"), "org");
        when(mapper.toDto(event)).thenReturn(dto);
        return dto;
    }

    @Test
    void drainOnce_shouldSendEachFixedDateThenMarkItSent() {
        EventEntity first = EventEntity.builder().id("e1").dateFixSynced(false).build();
        EventEntity second = EventEntity.builder().id("e2").dateFixSynced(false).build();
        EventDateFixedDTO firstDto = pending(first);
        EventDateFixedDTO secondDto = pending(second);
        when(outbox.findPending(10)).thenReturn(Flux.just(first, second));
        when(publisher.publish(firstDto)).thenReturn(Mono.empty());
        when(publisher.publish(secondDto)).thenReturn(Mono.empty());
        when(outbox.markPublished("e1")).thenReturn(Mono.just(true));
        when(outbox.markPublished("e2")).thenReturn(Mono.just(true));

        StepVerifier.create(relay.drainOnce()).expectNext(2L).verifyComplete();

        verify(outbox).markPublished("e1");
        verify(outbox).markPublished("e2");
    }

    @Test
    void drainOnce_shouldLeaveADatePendingAndGoOn_whenKafkaFails() {
        EventEntity failing = EventEntity.builder().id("e1").build();
        EventEntity fine = EventEntity.builder().id("e2").build();
        EventDateFixedDTO failingDto = pending(failing);
        EventDateFixedDTO fineDto = pending(fine);
        when(outbox.findPending(10)).thenReturn(Flux.just(failing, fine));
        when(publisher.publish(failingDto)).thenReturn(Mono.error(new EventException(EventErrorCode.BROKER_UNAVAILABLE)));
        when(publisher.publish(fineDto)).thenReturn(Mono.empty());
        when(outbox.markPublished("e2")).thenReturn(Mono.just(true));

        StepVerifier.create(relay.drainOnce()).expectNext(1L).verifyComplete();

        verify(outbox, never()).markPublished("e1");
    }

    @Test
    void drainOnce_shouldSendNothing_whenNothingIsPending() {
        when(outbox.findPending(10)).thenReturn(Flux.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();
        verify(outbox, never()).markPublished(anyString());
    }

    @Test
    void start_shouldKeepPolling_evenAfterAFailedPass() {
        when(outbox.findPending(anyInt()))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("mongo down")))
                .thenReturn(Flux.empty());

        relay.start();
        try {
            assertThat(relay.isRunning()).isTrue();
            verify(outbox, timeout(2000).atLeast(3)).findPending(10);
        } finally {
            relay.stop();
        }
        assertThat(relay.isRunning()).isFalse();
    }

    @Test
    void stop_shouldBeHarmless_whenNeverStarted() {
        relay.stop();

        assertThat(relay.isRunning()).isFalse();
    }
}
