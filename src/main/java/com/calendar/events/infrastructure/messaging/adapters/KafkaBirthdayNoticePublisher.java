package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.messaging.models.BirthdayNoticeDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;

/** Sends a birthday notice to {@code BIRTHDAY_NOTICES}, keyed by its recipient. */
@Slf4j
@Component
public class KafkaBirthdayNoticePublisher {

    static final String DESTINATION = "birthdayNotices-out-0";

    private final StreamBridge streamBridge;

    public KafkaBirthdayNoticePublisher(StreamBridge streamBridge) {
        this.streamBridge = streamBridge;
    }

    /** Completes once Kafka has the record; any failure arrives as an {@link EventException}. */
    public Mono<Void> publish(BirthdayNoticeDTO notice) {
        return Mono.fromCallable(() -> streamBridge.send(DESTINATION, message(notice)))
                // StreamBridge.send blocks: keep it off the event loop threads.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sent -> Boolean.TRUE.equals(sent)
                        ? Mono.<Void>empty()
                        : Mono.<Void>error(unavailable(notice, "the record was refused")))
                .onErrorMap(error -> !(error instanceof EventException),
                        error -> unavailable(notice, error.getMessage()));
    }

    static Message<BirthdayNoticeDTO> message(BirthdayNoticeDTO notice) {
        return MessageBuilder.withPayload(notice)
                .setHeader(KafkaHeaders.KEY, notice.recipientId().getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private static EventException unavailable(BirthdayNoticeDTO notice, String reason) {
        log.warn("Could not send the {} notice for {} to Kafka: {}", notice.type(), notice.recipientId(), reason);
        return new EventException(EventErrorCode.BROKER_UNAVAILABLE);
    }
}
