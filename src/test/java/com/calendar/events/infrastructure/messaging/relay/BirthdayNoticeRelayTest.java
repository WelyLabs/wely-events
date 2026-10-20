package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.messaging.adapters.KafkaBirthdayNoticePublisher;
import com.calendar.events.infrastructure.messaging.mappers.BirthdayNoticeMapper;
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
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BirthdayNoticeRelayTest {

    @Mock
    private BirthdayNoticeOutbox outbox;
    @Mock
    private KafkaBirthdayNoticePublisher publisher;

    private final BirthdayNoticeMapper mapper = Mappers.getMapper(BirthdayNoticeMapper.class);
    private BirthdayNoticeRelay relay;

    @BeforeEach
    void setUp() {
        relay = new BirthdayNoticeRelay(outbox, publisher, mapper, Duration.ofMillis(50), 10);
    }

    private static BirthdayNotice notice(String recipient) {
        return new BirthdayNotice(BirthdayNoticeType.FRIEND_BIRTHDAY, recipient, "lea", "Léa",
                LocalDate.parse("2027-03-12"), 0, null, null, null, null);
    }

    @Test
    void drainOnce_shouldSendEachNoticeThenMarkIt() {
        BirthdayNotice bob = notice("bob");
        BirthdayNotice zoe = notice("zoe");
        when(outbox.findPending(10)).thenReturn(Flux.just(bob, zoe));
        when(publisher.publish(mapper.toDto(bob))).thenReturn(Mono.empty());
        when(publisher.publish(mapper.toDto(zoe))).thenReturn(Mono.empty());
        when(outbox.markPublished(bob.key())).thenReturn(Mono.empty());
        when(outbox.markPublished(zoe.key())).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(2L).expectComplete().verify(Duration.ofSeconds(10));
        verify(outbox).markPublished("lea:bob:2027:FRIEND_BIRTHDAY");
    }

    @Test
    void drainOnce_shouldLeaveANoticeUnsentAndGoOn_whenKafkaFails() {
        BirthdayNotice failing = notice("bob");
        BirthdayNotice fine = notice("zoe");
        when(outbox.findPending(10)).thenReturn(Flux.just(failing, fine));
        when(publisher.publish(mapper.toDto(failing))).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE)));
        when(publisher.publish(mapper.toDto(fine))).thenReturn(Mono.empty());
        when(outbox.markPublished(fine.key())).thenReturn(Mono.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(1L).expectComplete().verify(Duration.ofSeconds(10));
        verify(outbox, never()).markPublished(failing.key());
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
