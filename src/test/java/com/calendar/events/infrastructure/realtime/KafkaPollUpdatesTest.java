package com.calendar.events.infrastructure.realtime;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.infrastructure.realtime.models.PollUpdateBroadcastDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaPollUpdatesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Poll STORED = new Poll(PollStatus.CLOSED, List.of(), null, "s1");

    private StreamBridge streamBridge;
    private EventRepository events;
    private KafkaPollUpdates updates;

    @BeforeEach
    void setUp() {
        streamBridge = mock(StreamBridge.class);
        events = mock(EventRepository.class);
        when(streamBridge.send(any(String.class), any())).thenReturn(true);
        updates = new KafkaPollUpdates(streamBridge, events);
    }

    @Test
    void publish_shouldAnnounceTheEventWithoutThePoll() {
        updates.publish("e1", new Poll(PollStatus.OPEN, List.of(), null, null));

        verify(streamBridge, timeout(TIMEOUT.toMillis()))
                .send(KafkaPollUpdates.DESTINATION, PollUpdateBroadcastDTO.changed("e1"));
    }

    @Test
    void publish_shouldNotFailTheCallerWhenTheSendIsRefusedOrThrows() {
        when(streamBridge.send(any(String.class), any())).thenReturn(false);
        assertThatCode(() -> updates.publish("e1", STORED)).doesNotThrowAnyException();

        when(streamBridge.send(any(String.class), any())).thenThrow(new IllegalStateException("broker down"));
        assertThatCode(() -> updates.publish("e1", STORED)).doesNotThrowAnyException();
    }

    @Test
    void disconnect_shouldBroadcastTheViewer() {
        updates.disconnect("bob");

        verify(streamBridge, timeout(TIMEOUT.toMillis()))
                .send(KafkaPollUpdates.DESTINATION, PollUpdateBroadcastDTO.disconnected("bob"));
    }

    @Test
    void stream_shouldDeliverTheStoredPollWhenAChangeComesBack() {
        when(events.findById("e1")).thenReturn(Mono.just(Event.builder().id("e1").poll(STORED).build()));

        StepVerifier.create(updates.stream("e1", "bob"))
                .then(() -> updates.accept(PollUpdateBroadcastDTO.changed("e1")).subscribe())
                .expectNext(STORED)
                .thenCancel()
                .verify(TIMEOUT);
    }

    @Test
    void accept_shouldNotReadAnEventNobodyHereWatches() {
        StepVerifier.create(updates.accept(PollUpdateBroadcastDTO.changed("e1"))).verifyComplete();

        verify(events, never()).findById(any());
    }

    @Test
    void accept_shouldPushNothingWhenTheEventHasNoPollAnyMore() {
        when(events.findById("e1")).thenReturn(Mono.just(Event.builder().id("e1").build()));

        StepVerifier.create(updates.stream("e1", "bob"))
                .then(() -> updates.accept(PollUpdateBroadcastDTO.changed("e1")).subscribe())
                .expectNoEvent(Duration.ofMillis(50))
                .thenCancel()
                .verify(TIMEOUT);
    }

    @Test
    void accept_shouldEndTheViewersStreamsOnADisconnection() {
        StepVerifier.create(updates.stream("e1", "bob"))
                .then(() -> updates.accept(PollUpdateBroadcastDTO.disconnected("bob")).subscribe())
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void accept_shouldDropAnUnknownOrIncompleteRecord() {
        StepVerifier.create(updates.accept(new PollUpdateBroadcastDTO("RENAMED", "e1", null))).verifyComplete();
        StepVerifier.create(updates.accept(new PollUpdateBroadcastDTO(PollUpdateBroadcastDTO.CHANGED, null, null)))
                .verifyComplete();

        verify(events, never()).findById(any());
    }
}
