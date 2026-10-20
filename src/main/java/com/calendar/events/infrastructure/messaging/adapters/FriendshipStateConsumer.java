package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.messaging.models.FriendshipStateDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Consumes wely-social's compacted {@code FRIENDSHIP_STATE} through a function the binder subscribes to,
 * keeping the {@code friendships} projection. A value is the pair while they are friends, applied under
 * the version guard; a tombstone (null value) ends the friendship named by the record key. Compaction keeps
 * the latest record per key, so a new consumer group rebuilds the projection from the earliest offset.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.friendship-state.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class FriendshipStateConsumer {

    static final String TOPIC = "FRIENDSHIP_STATE";
    static final int MAX_RETRIES = 5;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final String KEY_SEPARATOR = ":";

    private final FriendshipService friendshipService;
    private final ObjectMapper objectMapper;
    private final TechnicalErrorCounter technicalErrors;

    public FriendshipStateConsumer(FriendshipService friendshipService, ObjectMapper objectMapper,
                                   TechnicalErrorCounter technicalErrors) {
        this.friendshipService = friendshipService;
        this.objectMapper = objectMapper;
        this.technicalErrors = technicalErrors;
    }

    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> friendshipState() {
        return records -> records.concatMap(this::handle).then();
    }

    // Typed Message<?>: Spring Cloud Function hands a tombstone over with a KafkaNull payload, not bytes.
    private Mono<Void> handle(Message<?> record) {
        Optional<KeyPair> key = pairOfKey(record.getHeaders().get(KafkaHeaders.RECEIVED_KEY));
        Object payload = record.getPayload();
        if (payload == null || payload instanceof KafkaNull) {
            return key.map(pair -> endFriendship(pair.userId(), pair.otherId()))
                    .orElseGet(() -> {
                        log.error("{} tombstone dropped: its key is not two distinct canonical UUIDs joined by ':'", TOPIC);
                        return Mono.empty();
                    });
        }
        return Mono.fromCallable(() -> objectMapper.readValue((byte[]) payload, FriendshipStateDTO.class))
                .flatMap(dto -> state(dto)
                        .filter(state -> key.isEmpty() || state.pairId().equals(key.get().pairId()))
                        .map(this::applyState)
                        .orElseGet(() -> {
                            log.error("{} record dropped: needs two distinct canonical UUIDs matching the key, friends, "
                                    + "a version, and a known closeness when they are friends", TOPIC);
                            return Mono.empty();
                        }))
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    log.error("{} record unreadable, dropped: {}", TOPIC, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> applyState(Friendship state) {
        return withRetries(Mono.defer(() -> friendshipService.applyState(state)), state.pairId(),
                "applied, version=" + state.version(), "ignored, stale or naming a deleted account, version=" + state.version());
    }

    private Mono<Void> endFriendship(String userId, String otherId) {
        String pairId = Friendship.pairIdOf(userId, otherId);
        return withRetries(Mono.defer(() -> friendshipService.applyTombstone(userId, otherId)), pairId,
                "tombstone ended the friendship", "tombstone ignored, not friends or ending an older state");
    }

    private Mono<Void> withRetries(Mono<Boolean> step, String pairId, String applied, String ignored) {
        return step
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF).maxBackoff(MAX_BACKOFF))
                .doOnNext(done -> {
                    if (done) {
                        log.info("{} {}, pair={}", TOPIC, applied, pairId);
                    } else {
                        log.debug("{} {}, pair={}", TOPIC, ignored, pairId);
                    }
                })
                .then()
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_KAFKA);
                    log.warn("{} not applied, pair={}: {}", TOPIC, pairId, String.valueOf(rootCause(error).getMessage()));
                    return Mono.empty();
                });
    }

    /** The pair the value describes; empty for anything malformed. */
    static Optional<Friendship> state(FriendshipStateDTO dto) {
        Optional<String> a = FriendshipChangedConsumer.canonical(dto.a());
        Optional<String> b = FriendshipChangedConsumer.canonical(dto.b());
        if (dto.friends() == null || dto.version() == null || a.isEmpty() || b.isEmpty()) {
            return Optional.empty();
        }
        try {
            Closeness closeness = dto.closeness() == null ? null : Closeness.valueOf(dto.closeness());
            return Optional.of(new Friendship(a.get(), b.get(), dto.friends(), closeness, dto.since(), dto.version()));
        } catch (IllegalArgumentException malformed) {
            // An unknown closeness, one user twice, or friends without a closeness.
            return Optional.empty();
        }
    }

    /** The two ids of an {@code a:b} key, bytes by default, a string with a string deserializer. */
    static Optional<KeyPair> pairOfKey(Object key) {
        String text = key instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8)
                : key instanceof String string ? string : null;
        if (text == null) {
            return Optional.empty();
        }
        String[] parts = text.split(KEY_SEPARATOR, -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        Optional<String> a = FriendshipChangedConsumer.canonical(parts[0]);
        Optional<String> b = FriendshipChangedConsumer.canonical(parts[1]);
        if (a.isEmpty() || b.isEmpty() || a.get().equals(b.get())) {
            return Optional.empty();
        }
        return Optional.of(new KeyPair(a.get(), b.get()));
    }

    /** The two users a record key names. */
    record KeyPair(String userId, String otherId) {
        String pairId() {
            return Friendship.pairIdOf(userId, otherId);
        }
    }

    // Retry exhaustion wraps the last failure.
    private static Throwable rootCause(Throwable error) {
        return error.getCause() != null ? error.getCause() : error;
    }
}
