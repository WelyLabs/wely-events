package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.services.BirthdayService;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.messaging.models.UserBirthdayEventDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.MonthDay;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Consumes wely-users' {@code USER_BIRTHDAY} through a function the binder subscribes to, keeping each user's birthday. */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.user-birthday.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class UserBirthdayConsumer {

    static final String TOPIC = "USER_BIRTHDAY";
    static final int MAX_RETRIES = 5;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final BirthdayService birthdayService;
    private final ObjectMapper objectMapper;
    private final TechnicalErrorCounter technicalErrors;

    public UserBirthdayConsumer(BirthdayService birthdayService, ObjectMapper objectMapper,
                                TechnicalErrorCounter technicalErrors) {
        this.birthdayService = birthdayService;
        this.objectMapper = objectMapper;
        this.technicalErrors = technicalErrors;
    }

    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> userBirthday() {
        return records -> records.concatMap(this::handle).then();
    }

    private Mono<Void> handle(Message<byte[]> record) {
        return Mono.fromCallable(() -> objectMapper.readValue(record.getPayload(), UserBirthdayEventDTO.class))
                .flatMap(dto -> {
                    Optional<String> userId = canonical(dto.userId());
                    Optional<MonthDay> birthday = monthDay(dto);
                    if (userId.isEmpty() || birthday.isEmpty() || dto.displayName() == null || dto.displayName().isBlank()) {
                        log.error("{} record dropped: needs a canonical UUID userId, a displayName and a real day and month",
                                TOPIC);
                        return Mono.empty();
                    }
                    return record(userId.get(), dto.displayName().strip(), birthday.get());
                })
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    log.error("{} record unreadable, dropped: {}", TOPIC, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> record(String userId, String name, MonthDay birthday) {
        return Mono.defer(() -> birthdayService.recordBirthday(userId, name, birthday))
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF).maxBackoff(MAX_BACKOFF))
                .doOnNext(saved -> log.info("{} applied, userId={}", TOPIC, userId))
                .then()
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    log.warn("{} not applied, userId={}: {}", TOPIC, userId,
                            String.valueOf(rootCause(error).getMessage()));
                    return Mono.empty();
                });
    }

    private static Optional<MonthDay> monthDay(UserBirthdayEventDTO dto) {
        if (dto.month() == null || dto.day() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(MonthDay.of(dto.month(), dto.day()));
        } catch (DateTimeException notADay) {
            return Optional.empty();
        }
    }

    /** As for USER_DELETED: only a canonical UUID passes, in lowercase like the businessIds in tokens. */
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
