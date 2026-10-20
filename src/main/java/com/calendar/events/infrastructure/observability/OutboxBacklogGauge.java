package com.calendar.events.infrastructure.observability;

import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventDateFixedOutboxAdapter;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventGroupOutboxAdapter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * {@code wely.outbox.pending{outbox}}: how many records each relay has yet to send to Kafka, one series per
 * outbox. A growing value means a relay is stuck, which the relays' own logs only show one record at a time.
 *
 * <p>A gauge is read on the scrape thread and must not block, so it reports an {@link AtomicLong} that a
 * reactive count refreshes in the background. A failed count keeps the last value and is counted as a
 * technical failure of a job: the backlog is unknown, not empty.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.outbox-backlog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxBacklogGauge implements SmartLifecycle {

    static final String METRIC = "wely.outbox.pending";
    static final String OUTBOX_TAG = "outbox";

    static final String EVENT_GROUP = "event-group";
    static final String EVENT_DELETION = "event-deletion";
    static final String EVENT_DATE_FIXED = "event-date-fixed";
    static final String BIRTHDAY_NOTICE = "birthday-notice";
    static final String FRIENDSHIP_NOTICE = "friendship-notice";

    private final Map<String, Supplier<Mono<Long>>> counts = new LinkedHashMap<>();
    private final Map<String, AtomicLong> pending = new LinkedHashMap<>();
    private final TechnicalErrorCounter technicalErrors;
    private final Duration refreshInterval;

    private volatile Disposable loop;

    public OutboxBacklogGauge(MongoEventGroupOutboxAdapter groups, EventDeletionOutbox deletions,
                              MongoEventDateFixedOutboxAdapter dateFixes, BirthdayNoticeOutbox birthdayNotices,
                              FriendshipNoticeOutbox friendshipNotices, MeterRegistry meterRegistry,
                              TechnicalErrorCounter technicalErrors,
                              @Value("${app.outbox-backlog.refresh-interval:30s}") Duration refreshInterval) {
        this.technicalErrors = technicalErrors;
        this.refreshInterval = refreshInterval;
        counts.put(EVENT_GROUP, groups::countPending);
        counts.put(EVENT_DELETION, deletions::countPending);
        counts.put(EVENT_DATE_FIXED, dateFixes::countPending);
        counts.put(BIRTHDAY_NOTICE, birthdayNotices::countPending);
        counts.put(FRIENDSHIP_NOTICE, friendshipNotices::countPending);
        counts.keySet().forEach(outbox -> {
            AtomicLong value = new AtomicLong();
            pending.put(outbox, value);
            Gauge.builder(METRIC, value, AtomicLong::get)
                    .description("Records not yet sent to Kafka by the outbox relay")
                    .tag(OUTBOX_TAG, outbox)
                    .register(meterRegistry);
        });
    }

    @Override
    public void start() {
        // Each refresh catches its own errors: one reaching interval would end the loop.
        loop = Flux.interval(Duration.ZERO, refreshInterval)
                .concatMap(tick -> refreshOnce())
                .subscribe(
                        done -> { },
                        error -> log.error("Outbox backlog gauge terminated and will not restart", error));
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

    /** One refresh of every outbox, one after the other; a failed count leaves its last value as it was. */
    Mono<Void> refreshOnce() {
        return Flux.fromIterable(counts.entrySet())
                .concatMap(outbox -> Mono.defer(outbox.getValue())
                        .doOnNext(pending.get(outbox.getKey())::set)
                        .onErrorResume(error -> {
                            technicalErrors.count(error, GlobalErrorHandler.SOURCE_JOB);
                            log.warn("Could not count the {} outbox; keeping its last value: {}", outbox.getKey(),
                                    error.toString());
                            return Mono.empty();
                        }))
                .then();
    }
}
