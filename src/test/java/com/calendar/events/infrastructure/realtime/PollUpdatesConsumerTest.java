package com.calendar.events.infrastructure.realtime;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.exception.TechnicalErrorCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PollUpdatesConsumerTest {

    private static Message<byte[]> record(String json) {
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    @Test
    void pollUpdates_shouldHandEachReadableRecordOnAndSkipTheUnreadable() {
        Poll stored = new Poll(PollStatus.OPEN, List.of(), null, null);
        EventRepository events = mock(EventRepository.class);
        when(events.findById("e1")).thenReturn(Mono.just(Event.builder().id("e1").poll(stored).build()));
        KafkaPollUpdates updates = new KafkaPollUpdates(mock(StreamBridge.class), events);
        PollUpdatesConsumer consumer = new PollUpdatesConsumer(updates, JsonMapper.builder().build(),
                new TechnicalErrorCounter(new SimpleMeterRegistry()));

        Flux<Message<byte[]>> records = Flux.just(record("not json"), record("""
                {"kind":"CHANGED","eventId":"e1"}"""));

        StepVerifier.create(updates.stream("e1", "bob"))
                .then(() -> consumer.pollUpdates().apply(records).subscribe())
                .expectNext(stored)
                .thenCancel()
                .verify(Duration.ofSeconds(5));
    }
}
