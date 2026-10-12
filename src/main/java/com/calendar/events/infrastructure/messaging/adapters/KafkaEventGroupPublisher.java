package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sends an event's member list to {@code EVENT_GROUP_CHANGED}. */
@Slf4j
@Component
public class KafkaEventGroupPublisher {

    static final String DESTINATION = "eventGroup-out-0";

    private final StreamBridge streamBridge;

    public KafkaEventGroupPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /**
     * Completes once Kafka has the record. A refusal or a broker failure arrives as an
     * {@link EventException}, never as the binder's own exception.
     */
    public Mono<Void> publish(EventGroupChangedDTO group) {
        return Mono.fromCallable(() -> streamBridge.send(DESTINATION, group))
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(unavailable(group, "the record was refused")))
                .onErrorMap(error -> !(error instanceof EventException),
                        error -> unavailable(group, error.getMessage()));
    }

    private static EventException unavailable(EventGroupChangedDTO group, String reason) {
        log.warn("Could not send the group of event {} to Kafka: {}", group.eventId(), reason);
        return new EventException(EventErrorCode.BROKER_UNAVAILABLE);
    }
}
