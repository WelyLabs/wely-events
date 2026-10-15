package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.domain.models.EventDeletion;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.infrastructure.messaging.adapters.KafkaEventGroupPublisher;
import com.calendar.events.infrastructure.messaging.mappers.EventGroupMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Background loop that tells wely-chat which events were deleted, so it closes their groups.
 *
 * <p>A deleted event takes its document, and so its group outbox, with it: the deletion leaves a
 * tombstone in {@code event_deletions} instead. Each pass sends the tombstones on the group topic,
 * keyed by event like the member lists, so the deletion lands after them on the same partition,
 * then removes each one sent. A crash between the two sends it again, which wely-chat absorbs.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.event-deletion.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EventDeletionRelay implements SmartLifecycle {

    private final EventDeletionOutbox outbox;
    private final KafkaEventGroupPublisher publisher;
    private final EventGroupMapper mapper;
    private final Duration pollInterval;
    private final int batchSize;

    private volatile Disposable loop;

    public EventDeletionRelay(EventDeletionOutbox outbox,
                              KafkaEventGroupPublisher publisher,
                              EventGroupMapper mapper,
                              @Value("${app.event-deletion.relay.poll-interval:2s}") Duration pollInterval,
                              @Value("${app.event-deletion.relay.batch-size:100}") int batchSize) {
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
                    log.error("Event deletion relay pass failed; next attempt in {}", pollInterval, error);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.delayElements(pollInterval))
                .subscribe(
                        count -> { },
                        error -> log.error("Event deletion relay terminated and will not restart", error));
        log.info("Event deletion relay started: every {}, in batches of {}", pollInterval, batchSize);
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

    /** One pass. Returns how many deletions were sent. */
    Mono<Long> drainOnce() {
        return outbox.findPending(batchSize)
                .concatMap(this::send)
                .count()
                .doOnNext(sent -> {
                    if (sent > 0) {
                        log.info("Event deletion relay sent {} deletion(s)", sent);
                    }
                });
    }

    /**
     * A failure costs this deletion a retry next pass, not the rest of the batch. The removal is
     * deferred: built eagerly, it would run even when the send fails.
     */
    private Mono<EventDeletion> send(EventDeletion deletion) {
        return publisher.publish(mapper.toDto(deletion))
                .then(Mono.defer(() -> outbox.markPublished(deletion.eventId())))
                .thenReturn(deletion)
                .onErrorResume(error -> {
                    log.warn("Could not send the deletion of event {}: {}", deletion.eventId(), error.toString());
                    return Mono.empty();
                });
    }
}
