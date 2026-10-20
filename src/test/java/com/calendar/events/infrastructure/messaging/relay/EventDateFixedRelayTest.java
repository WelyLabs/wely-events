package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.messaging.adapters.KafkaEventDateFixedPublisher;
import com.calendar.events.infrastructure.messaging.mappers.EventDateFixedMapper;
import com.calendar.events.infrastructure.messaging.models.EventDateFixedDTO;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventDateFixedOutboxAdapter;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventDateFixedRelayTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private double technicalFailures(String code) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_OUTBOX).count();
    }

    @Mock
    private MongoEventDateFixedOutboxAdapter outbox;
    @Mock
    private KafkaEventDateFixedPublisher publisher;
    @Mock
    private EventDateFixedMapper mapper;

    private EventDateFixedRelay relay;

    @BeforeEach
    void setUp() {
        relay = new EventDateFixedRelay(outbox, publisher, mapper, Duration.ofMillis(50), 10, new TechnicalErrorCounter(meterRegistry));
    }

    private EventDateFixedDTO pending(EventEntity event) {
        EventDateFixedDTO dto = new EventDateFixedDTO(event.getId(), "Dinner",
                Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"), "org", null, null);
        when(mapper.toDto(event)).thenReturn(dto);
        return dto;
    }

    @Test
    void drainOnce_shouldSendEachFixedDateThenMarkItSent() {
        EventEntity first = EventEntity.builder().id("e1").dateFixSynced(false).dateFixVersion(1L).build();
        EventEntity second = EventEntity.builder().id("e2").dateFixSynced(false).build();
        EventDateFixedDTO firstDto = pending(first);
        EventDateFixedDTO secondDto = pending(second);
        when(outbox.findPending(10)).thenReturn(Flux.just(first, second));
        when(publisher.publish(firstDto)).thenReturn(Mono.empty());
        when(publisher.publish(secondDto)).thenReturn(Mono.empty());
        when(outbox.markPublished("e1", 1L)).thenReturn(Mono.just(true));
        when(outbox.markPublished("e2", null)).thenReturn(Mono.just(true));

        StepVerifier.create(relay.drainOnce()).expectNext(2L).expectComplete().verify(Duration.ofSeconds(10));

        // Each with the version it was read at: a date moved meanwhile stays pending.
        verify(outbox).markPublished("e1", 1L);
        verify(outbox).markPublished("e2", null);
    }

    @Test
    void drainOnce_shouldLeaveADatePendingAndGoOn_whenKafkaFails() {
        EventEntity failing = EventEntity.builder().id("e1").build();
        EventEntity fine = EventEntity.builder().id("e2").build();
        EventDateFixedDTO failingDto = pending(failing);
        EventDateFixedDTO fineDto = pending(fine);
        when(outbox.findPending(10)).thenReturn(Flux.just(failing, fine));
        when(publisher.publish(failingDto)).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE)));
        when(publisher.publish(fineDto)).thenReturn(Mono.empty());
        when(outbox.markPublished("e2", null)).thenReturn(Mono.just(true));

        StepVerifier.create(relay.drainOnce()).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox, never()).markPublished(eq("e1"), any());
        assertThat(technicalFailures(TechnicalErrorCode.BROKER_UNAVAILABLE.getCode())).isEqualTo(1.0);
    }

    @Test
    void drainOnce_shouldSendNothing_whenNothingIsPending() {
        when(outbox.findPending(10)).thenReturn(Flux.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));
        verify(outbox, never()).markPublished(anyString(), any());
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
            // The failed pass is counted before the next one starts; the passes after it found nothing.
            assertThat(technicalFailures(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
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
