package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipChange;
import com.calendar.events.domain.models.FriendshipChangeType;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.infrastructure.messaging.models.FriendshipChangedEventDTO;
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

/**
 * Consumes wely-social's {@code FRIENDSHIP_CHANGED} through a function the binder subscribes to, keeping
 * the {@code friendships} projection and its history. Records arrive in order per pair (keyed by pair);
 * the version still drops a stale one, from a replay or a rebalance.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.friendships.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class FriendshipChangedConsumer {

    static final String TOPIC = "FRIENDSHIP_CHANGED";
    static final int MAX_RETRIES = 5;
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final FriendshipService friendshipService;
    private final ObjectMapper objectMapper;

    public FriendshipChangedConsumer(FriendshipService friendshipService, ObjectMapper objectMapper) {
        this.friendshipService = friendshipService;
        this.objectMapper = objectMapper;
    }

    @Bean
    public Function<Flux<Message<byte[]>>, Mono<Void>> friendships() {
        return records -> records.concatMap(this::handle).then();
    }

    private Mono<Void> handle(Message<byte[]> record) {
        return Mono.fromCallable(() -> objectMapper.readValue(record.getPayload(), FriendshipChangedEventDTO.class))
                .flatMap(dto -> change(dto)
                        .map(this::record)
                        .orElseGet(() -> {
                            log.error("{} record dropped: needs a known type, two distinct canonical UUIDs, a version, "
                                    + "and a known closeness when they are friends", TOPIC);
                            return Mono.empty();
                        }))
                .onErrorResume(error -> {
                    log.error("{} record unreadable, dropped: {}", TOPIC, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> record(FriendshipChange change) {
        Friendship friendship = change.after();
        return Mono.defer(() -> friendshipService.record(change))
                .retryWhen(Retry.backoff(MAX_RETRIES, FIRST_BACKOFF).maxBackoff(MAX_BACKOFF))
                .doOnNext(stored -> {
                    if (stored) {
                        log.info("{} applied, pair={}, version={}, friends={}", TOPIC, friendship.pairId(),
                                friendship.version(), friendship.friends());
                    } else {
                        log.debug("{} ignored, stale or naming a deleted account, pair={}, version={}", TOPIC,
                                friendship.pairId(), friendship.version());
                    }
                })
                .then()
                .onErrorResume(error -> {
                    log.warn("{} not applied, pair={}, version={}: {}", TOPIC, friendship.pairId(),
                            friendship.version(), String.valueOf(rootCause(error).getMessage()));
                    return Mono.empty();
                });
    }

    /** The change the record describes; empty for anything malformed. An unknown previous closeness reads as none. */
    static Optional<FriendshipChange> change(FriendshipChangedEventDTO dto) {
        Optional<String> from = canonical(dto.fromUserId());
        Optional<String> to = canonical(dto.toUserId());
        if (dto.type() == null || dto.version() == null || from.isEmpty() || to.isEmpty()) {
            return Optional.empty();
        }
        try {
            FriendshipChangeType type = FriendshipChangeType.valueOf(dto.type());
            Closeness closeness = dto.closeness() == null ? null : Closeness.valueOf(dto.closeness());
            Friendship after = Friendship.after(type, from.get(), to.get(), closeness, dto.since(), dto.version());
            return Optional.of(new FriendshipChange(type, after, previousCloseness(dto.previousCloseness()),
                    dto.occurredAt()));
        } catch (IllegalArgumentException malformed) {
            // An unknown type or closeness, one user twice, or friends without a closeness.
            return Optional.empty();
        }
    }

    // Only the year in review reads it: an unknown value must not cost the projection its update.
    private static Closeness previousCloseness(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Closeness.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            return null;
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
