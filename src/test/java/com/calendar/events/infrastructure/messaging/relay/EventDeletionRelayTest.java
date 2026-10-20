package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.messaging.adapters.KafkaEventGroupPublisher;
import com.calendar.events.infrastructure.messaging.mappers.EventGroupMapper;
import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventDeletionRelayTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private double technicalFailures(String code) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_OUTBOX).count();
    }

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Mock
    private EventDeletionOutbox outbox;
    @Mock
    private KafkaEventGroupPublisher publisher;
    @Mock
    private EventGroupMapper mapper;

    private EventDeletionRelay relay;

    @BeforeEach
    void setUp() {
        relay = new EventDeletionRelay(outbox, publisher, mapper, Duration.ofMillis(50), 10, new TechnicalErrorCounter(meterRegistry));
    }

    private EventDeletion pending(String id) {
        EventDeletion deletion = new EventDeletion(id, "title " + id, 4L, NOW);
        when(mapper.toDto(deletion)).thenReturn(new EventGroupChangedDTO(id, "title " + id, null, List.of(), 4L, true));
        return deletion;
    }

    @Test
    void drainOnce_shouldSendEachDeletionThenDropItsTombstone() {
        EventDeletion first = pending("e1");
        EventDeletion second = pending("e2");
        when(outbox.findPending(10)).thenReturn(Flux.just(first, second));
        when(publisher.publish(mapper.toDto(first))).thenReturn(Mono.empty());
        when(publisher.publish(mapper.toDto(second))).thenReturn(Mono.empty());
        when(outbox.markPublished("e1")).thenReturn(Mono.empty());
        when(outbox.markPublished("e2")).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(2L).expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox).markPublished("e1");
        verify(outbox).markPublished("e2");
    }

    @Test
    void drainOnce_shouldKeepATombstoneAndGoOn_whenKafkaFails() {
        EventDeletion failing = pending("e1");
        EventDeletion fine = pending("e2");
        when(outbox.findPending(10)).thenReturn(Flux.just(failing, fine));
        when(publisher.publish(mapper.toDto(failing)))
                .thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE)));
        when(publisher.publish(mapper.toDto(fine))).thenReturn(Mono.empty());
        when(outbox.markPublished("e2")).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));

        verify(outbox, never()).markPublished("e1");
        assertThat(technicalFailures(TechnicalErrorCode.BROKER_UNAVAILABLE.getCode())).isEqualTo(1.0);
    }

    @Test
    void drainOnce_shouldSendNothing_whenNothingIsPending() {
        when(outbox.findPending(10)).thenReturn(Flux.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(0L).expectComplete().verify(Duration.ofSeconds(10));
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
