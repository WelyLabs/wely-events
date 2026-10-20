package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
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
     * Completes once Kafka has the record. A refusal or a broker failure arrives as a
     * {@link TechnicalException}, never as the binder's own exception.
     */
    public Mono<Void> publish(EventGroupChangedDTO group) {
        return Mono.fromCallable(() -> streamBridge.send(DESTINATION, group))
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(unavailable(group, "the record was refused")))
                .onErrorMap(error -> !(error instanceof TechnicalException),
                        error -> unavailable(group, error));
    }

    private static TechnicalException unavailable(EventGroupChangedDTO group, String reason) {
        log.warn("Could not send the group of event {} to Kafka: {}", group.eventId(), reason);
        return new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE);
    }

    /** The binder's own failure, kept as the cause. */
    private static TechnicalException unavailable(EventGroupChangedDTO group, Throwable error) {
        log.warn("Could not send the group of event {} to Kafka: {}", group.eventId(), error.getMessage());
        return new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE, error);
    }
}
