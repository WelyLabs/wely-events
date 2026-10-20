package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.messaging.adapters.KafkaEventDateFixedPublisher;
import com.calendar.events.infrastructure.messaging.mappers.EventDateFixedMapper;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventDateFixedOutboxAdapter;
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
 * Background loop that tells wely-chat about every date fixed by a poll or moved by the organizer,
 * on the same outbox pattern as {@link EventGroupRelay}: the fix or move flags the event document
 * in its own atomic update, each pass sends what is flagged, then clears the flag unless the date
 * moved again meanwhile, which the next pass sends. A crash between send and mark sends it again,
 * which the consumer must absorb.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.event-date-fixed.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class EventDateFixedRelay implements SmartLifecycle {

    private final MongoEventDateFixedOutboxAdapter outbox;
    private final KafkaEventDateFixedPublisher publisher;
    private final EventDateFixedMapper mapper;
    private final Duration pollInterval;
    private final int batchSize;
    private final TechnicalErrorCounter technicalErrors;

    private volatile Disposable loop;

    public EventDateFixedRelay(MongoEventDateFixedOutboxAdapter outbox,
                               KafkaEventDateFixedPublisher publisher,
                               EventDateFixedMapper mapper,
                               @Value("${app.event-date-fixed.relay.poll-interval:2s}") Duration pollInterval,
                               @Value("${app.event-date-fixed.relay.batch-size:100}") int batchSize,
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
                    log.error("Date-fixed relay pass failed; next attempt in {}", pollInterval, error);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.delayElements(pollInterval))
                .subscribe(
                        count -> { },
                        error -> {
                            technicalErrors.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                            log.error("Date-fixed relay terminated and will not restart", error);
                        });
        log.info("Date-fixed relay started: every {}, in batches of {}", pollInterval, batchSize);
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

    /** One pass. Returns how many fixed dates were sent. */
    Mono<Long> drainOnce() {
        return outbox.findPending(batchSize)
                .concatMap(this::send)
                .count()
                .doOnNext(sent -> {
                    if (sent > 0) {
                        log.info("Date-fixed relay sent {} date(s)", sent);
                    }
                });
    }

    /** A failure costs this event a retry next pass, not the rest of the batch. The mark is deferred. */
    private Mono<EventEntity> send(EventEntity event) {
        return publisher.publish(mapper.toDto(event))
                .then(Mono.defer(() -> outbox.markPublished(event.getId(), event.getDateFixVersion())))
                .thenReturn(event)
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                    log.warn("Could not send the fixed date of event {}: {}", event.getId(), error.toString());
                    return Mono.empty();
                });
    }
}
