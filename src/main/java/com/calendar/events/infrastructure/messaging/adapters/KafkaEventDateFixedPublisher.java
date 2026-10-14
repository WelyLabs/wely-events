package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.messaging.models.EventDateFixedDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sends a fixed date to {@code EVENT_DATE_FIXED}. */
@Slf4j
@Component
public class KafkaEventDateFixedPublisher {

    static final String DESTINATION = "eventDateFixed-out-0";

    private final StreamBridge streamBridge;

    public KafkaEventDateFixedPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /** Completes once Kafka has the record; any failure arrives as an {@link EventException}. */
    public Mono<Void> publish(EventDateFixedDTO dateFixed) {
        return Mono.fromCallable(() -> streamBridge.send(DESTINATION, dateFixed))
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(unavailable(dateFixed, "the record was refused")))
                .onErrorMap(error -> !(error instanceof EventException),
                        error -> unavailable(dateFixed, error.getMessage()));
    }

    private static EventException unavailable(EventDateFixedDTO dateFixed, String reason) {
        log.warn("Could not send the fixed date of event {} to Kafka: {}", dateFixed.eventId(), reason);
        return new EventException(EventErrorCode.BROKER_UNAVAILABLE);
    }
}
