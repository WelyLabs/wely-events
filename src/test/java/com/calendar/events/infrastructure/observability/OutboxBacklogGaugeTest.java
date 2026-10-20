package com.calendar.events.infrastructure.observability;

import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.FriendshipNoticeOutbox;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventDateFixedOutboxAdapter;
import com.calendar.events.infrastructure.persistence.adapters.MongoEventGroupOutboxAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxBacklogGaugeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Mock
    private MongoEventGroupOutboxAdapter groups;
    @Mock
    private EventDeletionOutbox deletions;
    @Mock
    private MongoEventDateFixedOutboxAdapter dateFixes;
    @Mock
    private BirthdayNoticeOutbox birthdayNotices;
    @Mock
    private FriendshipNoticeOutbox friendshipNotices;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxBacklogGauge gauge;

    @BeforeEach
    void setUp() {
        gauge = new OutboxBacklogGauge(groups, deletions, dateFixes, birthdayNotices, friendshipNotices, meterRegistry,
                new TechnicalErrorCounter(meterRegistry), Duration.ofMillis(50));
    }

    private double pending(String outbox) {
        return meterRegistry.get(OutboxBacklogGauge.METRIC).tag(OutboxBacklogGauge.OUTBOX_TAG, outbox).gauge().value();
    }

    private void counts(long group, long deletion, long dateFixed, long birthday, long friendship) {
        when(groups.countPending()).thenReturn(Mono.just(group));
        when(deletions.countPending()).thenReturn(Mono.just(deletion));
        when(dateFixes.countPending()).thenReturn(Mono.just(dateFixed));
        when(birthdayNotices.countPending()).thenReturn(Mono.just(birthday));
        when(friendshipNotices.countPending()).thenReturn(Mono.just(friendship));
    }

    @Test
    void constructor_shouldRegisterOneSeriesPerOutbox_atZero() {
        assertThat(meterRegistry.get(OutboxBacklogGauge.METRIC).gauges()).hasSize(5);
        assertThat(pending(OutboxBacklogGauge.EVENT_GROUP)).isZero();
    }

    @Test
    void refreshOnce_shouldExposeEachOutboxsCount() {
        counts(3, 1, 0, 12, 4);

        StepVerifier.create(gauge.refreshOnce()).expectComplete().verify(TIMEOUT);

        assertThat(pending(OutboxBacklogGauge.EVENT_GROUP)).isEqualTo(3.0);
        assertThat(pending(OutboxBacklogGauge.EVENT_DELETION)).isEqualTo(1.0);
        assertThat(pending(OutboxBacklogGauge.EVENT_DATE_FIXED)).isZero();
        assertThat(pending(OutboxBacklogGauge.BIRTHDAY_NOTICE)).isEqualTo(12.0);
        assertThat(pending(OutboxBacklogGauge.FRIENDSHIP_NOTICE)).isEqualTo(4.0);
    }

    @Test
    void refreshOnce_shouldKeepTheLastValueAndCountTheFailure_whenACountFails() {
        counts(3, 1, 0, 12, 4);
        StepVerifier.create(gauge.refreshOnce()).expectComplete().verify(TIMEOUT);
        when(groups.countPending()).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE)));
        when(deletions.countPending()).thenReturn(Mono.just(5L));

        StepVerifier.create(gauge.refreshOnce()).expectComplete().verify(TIMEOUT);

        assertThat(pending(OutboxBacklogGauge.EVENT_GROUP)).isEqualTo(3.0);
        // One failing outbox does not stop the others from refreshing.
        assertThat(pending(OutboxBacklogGauge.EVENT_DELETION)).isEqualTo(5.0);
        assertThat(meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, "EVT-TEC-005",
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_JOB).count()).isEqualTo(1.0);
    }

    @Test
    void start_shouldRefreshRightAway_thenOnItsInterval_untilStopped() {
        counts(2, 0, 0, 0, 0);

        gauge.start();
        try {
            assertThat(gauge.isRunning()).isTrue();
            verify(groups, timeout(2000).atLeast(2)).countPending();
            assertThat(pending(OutboxBacklogGauge.EVENT_GROUP)).isEqualTo(2.0);
        } finally {
            gauge.stop();
        }
        assertThat(gauge.isRunning()).isFalse();
    }

    @Test
    void stop_shouldBeHarmless_whenNeverStarted() {
        gauge.stop();

        assertThat(gauge.isRunning()).isFalse();
    }
}
