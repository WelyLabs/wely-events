package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Sends an event's member list to {@code EVENT_GROUP_CHANGED}. */
@Component
public class KafkaEventGroupPublisher {

    static final String DESTINATION = "eventGroup-out-0";

    private final StreamBridge streamBridge;

    public KafkaEventGroupPublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /** Completes once Kafka has the record; any problem arrives as an error signal. */
    public Mono<Void> publish(EventGroupChangedDTO group) {
        return Mono.fromCallable(() -> streamBridge.send(DESTINATION, group))
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(new IllegalStateException(
                                "Kafka refused the group of event " + group.eventId())));
    }
}
