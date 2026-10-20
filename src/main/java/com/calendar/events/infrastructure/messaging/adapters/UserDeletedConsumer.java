package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.services.AccountErasureService;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.messaging.models.UserDeletedEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Consumes wely-users' {@code USER_DELETED} through a function the binder subscribes to, erasing each account. */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.user-deleted.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class UserDeletedConsumer {

    static final String TOPIC = "USER_DELETED";
    static final int MAX_RETRIES = 5;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final AccountErasureService erasure;
    private final ObjectMapper objectMapper;
    private final TechnicalErrorCounter technicalErrors;

    public UserDeletedConsumer(AccountErasureService erasure, ObjectMapper objectMapper,
                               TechnicalErrorCounter technicalErrors) {
        this.erasure = erasure;
        this.objectMapper = objectMapper;
        this.technicalErrors = technicalErrors;
    }

    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> userDeleted() {
        return records -> records.concatMap(this::handle).then();
    }

    private Mono<Void> handle(Message<byte[]> record) {
        return Mono.fromCallable(() -> objectMapper.readValue(record.getPayload(), UserDeletedEventDTO.class))
                .flatMap(dto -> canonical(dto.userId())
                        .map(userId -> erase(userId, dto))
                        .orElseGet(() -> {
                            log.error("{} record dropped: userId is not a canonical UUID", TOPIC);
                            return Mono.empty();
                        }))
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    log.error("{} record unreadable, dropped: {}", TOPIC, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> erase(String userId, UserDeletedEventDTO dto) {
        return Mono.defer(() -> erasure.erase(userId, dto.deletedAt()))
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF).maxBackoff(MAX_BACKOFF))
                .doOnNext(report -> log.info("{} applied, userId={}, handedOver={}, deleted={}, left={}, ballots={}, "
                                + "responses={}, receipts={}", TOPIC, userId, report.eventsHandedOver(),
                        report.eventsDeleted(), report.eventsLeft(), report.ballotsWithdrawn(),
                        report.responsesCleared(), report.receiptsDropped()))
                .then()
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    log.warn("{} not applied, userId={}, reconciliation will retry: {}", TOPIC, userId,
                            String.valueOf(rootCause(error).getMessage()));
                    return Mono.empty();
                });
    }

    /** The id becomes a field name in MongoDB: only a canonical UUID passes, in lowercase. */
    private static Optional<String> canonical(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        try {
            return UUID.fromString(userId).toString().equalsIgnoreCase(userId)
                    ? Optional.of(userId.toLowerCase(Locale.ROOT))
                    : Optional.empty();
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    // Retry exhaustion wraps the last failure.
    private static Throwable rootCause(Throwable error) {
        return error.getCause() != null ? error.getCause() : error;
    }
}
