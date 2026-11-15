package com.calendar.events.infrastructure.realtime;

import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.realtime.models.PollUpdateBroadcastDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Function;

/**
 * Reads the poll-updates topic, in a consumer group of this instance's own, and hands each record to
 * {@link KafkaPollUpdates}. A record that fails is dropped: it is one missed live update.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.poll-updates.mode", havingValue = "kafka", matchIfMissing = true)
public class PollUpdatesConsumer {

    static final String TOPIC = "POLL_UPDATES";

    private final KafkaPollUpdates pollUpdates;
    private final ObjectMapper objectMapper;
    private final TechnicalErrorCounter technicalErrors;

    public PollUpdatesConsumer(KafkaPollUpdates pollUpdates, ObjectMapper objectMapper,
                               TechnicalErrorCounter technicalErrors) {
        this.pollUpdates = pollUpdates;
        this.objectMapper = objectMapper;
        this.technicalErrors = technicalErrors;
    }

    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> pollUpdates() {
        return records -> records.concatMap(this::handle).then();
    }

    private Mono<Void> handle(Message<byte[]> record) {
        return Mono.fromCallable(() -> objectMapper.readValue(record.getPayload(), PollUpdateBroadcastDTO.class))
                .flatMap(pollUpdates::accept)
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    log.error("{} record dropped: {}", TOPIC, error.getMessage());
                    return Mono.empty();
                });
    }
}
