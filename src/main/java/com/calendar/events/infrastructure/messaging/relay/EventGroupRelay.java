package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.messaging.adapters.KafkaEventGroupPublisher;
import com.calendar.events.infrastructure.messaging.mappers.EventGroupMapper;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventGroupOutboxAdapter;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Background loop that sends wely-chat the member list of every event whose participants changed.
 *
 * <p>The outbox is the event document itself: joining or leaving flags it in the same atomic
 * update, so a change cannot be saved without being sent, and there is no second collection to
 * keep in step — MongoDB has no transaction here to span one. Each pass sends what is flagged,
 * then clears the flag only if the version is still the one sent. A crash between the two sends
 * the list again, which wely-chat absorbs: delivery is at-least-once, the consumer idempotent.
 *
 * <p>Events stored before this existed carry no flag and count as unsent, so the first pass
 * after a deployment gives every existing event its group.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.event-group.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EventGroupRelay implements SmartLifecycle {

    private final MongoEventGroupOutboxAdapter outbox;
    private final KafkaEventGroupPublisher publisher;
    private final EventGroupMapper mapper;
    private final Duration pollInterval;
    private final int batchSize;
    private final TechnicalErrorCounter technicalErrors;

    private volatile Disposable loop;

    public EventGroupRelay(MongoEventGroupOutboxAdapter outbox,
                           KafkaEventGroupPublisher publisher,
                           EventGroupMapper mapper,
                           @Value("${app.event-group.relay.poll-interval:2s}") Duration pollInterval,
                           @Value("${app.event-group.relay.batch-size:100}") int batchSize,
                           TechnicalErrorCounter technicalErrors) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.mapper = mapper;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
        this.technicalErrors = technicalErrors;
    }

    @Override
    public void start() {
        // Errors are caught inside the repeated Mono: one reaching repeatWhen would stop the loop.
        loop = Mono.defer(this::drainOnce)
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                    log.error("Event group relay pass failed; next attempt in {}", pollInterval, error);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.delayElements(pollInterval))
                .subscribe(
                        count -> { },
                        error -> {
                            technicalErrors.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                            log.error("Event group relay terminated and will not restart", error);
                        });
        log.info("Event group relay started: every {}, in batches of {}", pollInterval, batchSize);
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

    /** One pass. Returns how many groups were sent. */
    Mono<Long> drainOnce() {
        return outbox.findPending(batchSize)
                .concatMap(this::send)
                .count()
                .doOnNext(sent -> {
                    if (sent > 0) {
                        log.info("Event group relay sent {} group(s)", sent);
                    }
                });
    }

    /**
     * A failure costs this event a retry next pass, not the rest of the batch. The mark is deferred:
     * built eagerly, it would run even when the send fails.
     */
    private Mono<EventEntity> send(EventEntity event) {
        return publisher.publish(mapper.toDto(event))
                .then(Mono.defer(() -> outbox.markPublished(event.getId(), event.getGroupVersion())))
                .thenReturn(event)
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                    log.warn("Could not send the group of event {}: {}", event.getId(), error.toString());
                    return Mono.empty();
                });
    }
}
