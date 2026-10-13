package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.messaging.adapters.KafkaEventGroupPublisher;
import com.calendar.events.infrastructure.messaging.mappers.EventGroupMapper;
import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventGroupOutboxAdapter;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventGroupRelayTest {

    @Mock
    private MongoEventGroupOutboxAdapter outbox;
    @Mock
    private KafkaEventGroupPublisher publisher;
    @Mock
    private EventGroupMapper mapper;

    private EventGroupRelay relay;

    @BeforeEach
    void setUp() {
        relay = new EventGroupRelay(outbox, publisher, mapper, Duration.ofMillis(50), 10);
    }

    private EventEntity pending(String id, Long version) {
        EventEntity event = EventEntity.builder().id(id).groupVersion(version).build();
        when(mapper.toDto(event)).thenReturn(new EventGroupChangedDTO(id, "title " + id, "org", List.of(), version == null ? 0 : version));
        return event;
    }

    @Test
    void drainOnce_shouldSendEachGroupThenMarkTheVersionSent() {
        EventEntity first = pending("e1", 2L);
        EventEntity second = pending("e2", null);
        when(outbox.findPending(10)).thenReturn(Flux.just(first, second));
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(outbox.markPublished("e1", 2L)).thenReturn(Mono.just(true));
        when(outbox.markPublished("e2", null)).thenReturn(Mono.just(true));

        StepVerifier.create(relay.drainOnce()).expectNext(2L).verifyComplete();

        verify(outbox).markPublished("e1", 2L);
        verify(outbox).markPublished("e2", null);
    }

    @Test
    void drainOnce_shouldLeaveAGroupPendingAndGoOn_whenKafkaFails() {
        EventEntity failing = pending("e1", 1L);
        EventEntity fine = pending("e2", 1L);
        when(outbox.findPending(10)).thenReturn(Flux.just(failing, fine));
        when(publisher.publish(mapper.toDto(failing)))
                .thenReturn(Mono.error(new EventException(EventErrorCode.BROKER_UNAVAILABLE)));
        when(publisher.publish(mapper.toDto(fine))).thenReturn(Mono.empty());
        when(outbox.markPublished("e2", 1L)).thenReturn(Mono.just(true));

        StepVerifier.create(relay.drainOnce()).expectNext(1L).verifyComplete();

        verify(outbox, never()).markPublished("e1", 1L);
    }

    @Test
    void drainOnce_shouldSendNothing_whenNothingIsPending() {
        when(outbox.findPending(10)).thenReturn(Flux.empty());

        StepVerifier.create(relay.drainOnce()).expectNext(0L).verifyComplete();
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
