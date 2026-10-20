package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.messaging.adapters.KafkaFriendshipNoticePublisher;
import com.calendar.events.infrastructure.messaging.mappers.FriendshipNoticeMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipNoticeRelayTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private double technicalFailures(String code) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_OUTBOX).count();
    }

    @Mock
    private FriendshipNoticeOutbox outbox;
    @Mock
    private KafkaFriendshipNoticePublisher publisher;

    private final FriendshipNoticeMapper mapper = Mappers.getMapper(FriendshipNoticeMapper.class);
    private FriendshipNoticeRelay relay;

    @BeforeEach
    void setUp() {
        relay = new FriendshipNoticeRelay(outbox, publisher, mapper, Duration.ofMillis(50), 10, new TechnicalErrorCounter(meterRegistry));
    }

    private static FriendshipNotice notice(String recipient) {
        return FriendshipNotice.anniversary(recipient, "lea", "Léa", Closeness.CLOSE, 1,
                Instant.parse("2025-10-18T09:00:00Z"), LocalDate.parse("2026-10-18"));
    }

    @Test
    void drainOnce_shouldSendEachNoticeThenMarkIt() {
        FriendshipNotice bob = notice("bob");
        FriendshipNotice zoe = notice("zoe");
        when(outbox.findPending(10)).thenReturn(Flux.just(bob, zoe));
        when(publisher.publish(mapper.toDto(bob))).thenReturn(Mono.empty());
        when(publisher.publish(mapper.toDto(zoe))).thenReturn(Mono.empty());
        when(outbox.markPublished(bob.key())).thenReturn(Mono.empty());
        when(outbox.markPublished(zoe.key())).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(2L).expectComplete().verify(Duration.ofSeconds(10));
        verify(outbox).markPublished("FRIENDSHIP_ANNIVERSARY:bob:lea:1");
    }

    @Test
    void drainOnce_shouldLeaveANoticeUnsentAndGoOn_whenKafkaFails() {
        FriendshipNotice failing = notice("bob");
        FriendshipNotice fine = notice("zoe");
        when(outbox.findPending(10)).thenReturn(Flux.just(failing, fine));
        when(publisher.publish(mapper.toDto(failing))).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE)));
        when(publisher.publish(mapper.toDto(fine))).thenReturn(Mono.empty());
        when(outbox.markPublished(fine.key())).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
        verify(outbox, never()).markPublished(failing.key());
        assertThat(technicalFailures(TechnicalErrorCode.BROKER_UNAVAILABLE.getCode())).isEqualTo(1.0);
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
