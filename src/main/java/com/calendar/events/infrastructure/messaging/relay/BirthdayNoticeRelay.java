package com.calendar.events.infrastructure.messaging.relay;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.messaging.adapters.KafkaBirthdayNoticePublisher;
import com.calendar.events.infrastructure.messaging.mappers.BirthdayNoticeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Background loop that sends the recorded birthday notices to wely-notifications, on the outbox
 * pattern of {@link EventDeletionRelay}: each pass sends what is not sent yet, then marks it. A
 * crash between the two sends it again, which the consumer must absorb.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.birthday-notices.relay", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class BirthdayNoticeRelay implements SmartLifecycle {

    private final BirthdayNoticeOutbox outbox;
    private final KafkaBirthdayNoticePublisher publisher;
    private final BirthdayNoticeMapper mapper;
    private final Duration pollInterval;
    private final int batchSize;
    private final TechnicalErrorCounter technicalErrors;

    private volatile Disposable loop;

    public BirthdayNoticeRelay(BirthdayNoticeOutbox outbox,
                               KafkaBirthdayNoticePublisher publisher,
                               BirthdayNoticeMapper mapper,
                               @Value("${app.birthday-notices.relay.poll-interval:5s}") Duration pollInterval,
                               @Value("${app.birthday-notices.relay.batch-size:100}") int batchSize,
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
                    log.error("Birthday notice relay pass failed; next attempt in {}", pollInterval, error);
                    return Mono.just(0L);
                })
                .repeatWhen(passes -> passes.delayElements(pollInterval))
                .subscribe(
                        count -> { },
                        error -> {
                            technicalErrors.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                            log.error("Birthday notice relay terminated and will not restart", error);
                        });
        log.info("Birthday notice relay started: every {}, in batches of {}", pollInterval, batchSize);
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
                        log.info("Birthday notice relay sent {} notice(s)", sent);
                    }
                });
    }

    /** A failure costs this notice a retry next pass, not the rest of the batch. The mark is deferred. */
    private Mono<BirthdayNotice> send(BirthdayNotice notice) {
        return publisher.publish(mapper.toDto(notice))
                .then(Mono.defer(() -> outbox.markPublished(notice.key())))
                .thenReturn(notice)
                .onErrorResume(error -> {
                    technicalErrors.count(error, GlobalErrorHandler.SOURCE_OUTBOX);
                    log.warn("Could not send birthday notice {}: {}", notice.key(), error.toString());
                    return Mono.empty();
                });
    }
}
