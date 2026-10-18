package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import com.calendar.events.infrastructure.messaging.adapters.KafkaFriendshipNoticePublisher;
import com.calendar.events.infrastructure.messaging.mappers.FriendshipNoticeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Background loop that sends the recorded friendship notices to wely-notifications, on the outbox
 * pattern of {@link EventDeletionRelay}: each pass sends what is not sent yet, then marks it. A
 * crash between the two sends it again, which the consumer must absorb.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.friendship-notices.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class FriendshipNoticeRelay implements SmartLifecycle {

    private final FriendshipNoticeOutbox outbox;
    private final KafkaFriendshipNoticePublisher publisher;
    private final FriendshipNoticeMapper mapper;
    private final Duration pollInterval;
    private final int batchSize;

    private volatile Disposable loop;

    public FriendshipNoticeRelay(FriendshipNoticeOutbox outbox,
                                 KafkaFriendshipNoticePublisher publisher,
                                 FriendshipNoticeMapper mapper,
                                 @Value("${app.friendship-notices.relay.poll-interval:5s}") Duration pollInterval,
                                 @Value("${app.friendship-notices.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.mapper = mapper;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
    }

    @Override
    public void start() {
        // Errors are caught inside the repeated Mono: one reaching repeatWhen would stop the loop.
        loop = Mono.defer(this::drainOnce)
                .onErrorResume(error -> {
                    log.error("Friendship notice relay pass failed; next attempt in {}", pollInterval, error);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.delayElements(pollInterval))
                .subscribe(
                        count -> { },
                        error -> log.error("Friendship notice relay terminated and will not restart", error));
        log.info("Friendship notice relay started: every {}, in batches of {}", pollInterval, batchSize);
    }

    @Override
    public void stop() {
        Disposable running = loop;
        loop = null;
        if (running != null) {
            running.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return loop != null;
    }

    /** One pass. Returns how many notices were sent. */
    Mono<Long> drainOnce() {
        return outbox.findPending(batchSize)
                .concatMap(this::send)
                .count()
                .doOnNext(sent -> {
                    if (sent > 0) {
                        log.info("Friendship notice relay sent {} notice(s)", sent);
                    }
                });
    }

    /** A failure costs this notice a retry next pass, not the rest of the batch. The mark is deferred. */
    private Mono<FriendshipNotice> send(FriendshipNotice notice) {
        return publisher.publish(mapper.toDto(notice))
                .then(Mono.defer(() -> outbox.markPublished(notice.key())))
                .thenReturn(notice)
                .onErrorResume(error -> {
                    log.warn("Could not send friendship notice {}: {}", notice.key(), error.toString());
                    return Mono.empty();
                });
    }
}
