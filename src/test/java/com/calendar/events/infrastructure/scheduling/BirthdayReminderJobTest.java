package com.calendar.events.infrastructure.scheduling;

import com.calendar.events.domain.services.BirthdayReminderService;
import com.calendar.events.domain.services.FriendshipReminderService;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BirthdayReminderJobTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private double technicalFailures(String code) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_JOB).count();
    }

    @Mock
    private BirthdayReminderService reminders;
    @Mock
    private FriendshipReminderService friendshipReminders;

    private BirthdayReminderJob jobAt(String instant) {
        return new BirthdayReminderJob(reminders, friendshipReminders, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
                new TechnicalErrorCounter(meterRegistry));
    }

    @Test
    void daily_shouldRecordTheBirthdayNotices_thenTheFriendshipOnes() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(3L));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.just(2L));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).expectComplete().verify(Duration.ofSeconds(10));
        InOrder order = inOrder(reminders, friendshipReminders);
        order.verify(reminders).recordDueNotices();
        order.verify(friendshipReminders).recordDueNotices();
        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    void daily_shouldSwallowAFailure_forTheNextRunToRetry_andStillRunTheFriendshipNotices() {
        when(reminders.recordDueNotices()).thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.just(0L));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipReminders).recordDueNotices();
        assertThat(technicalFailures(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    void daily_shouldSwallowAFriendshipNoticesFailure() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(0L));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).expectComplete().verify(Duration.ofSeconds(10));
        assertThat(technicalFailures(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    void catchUp_shouldRun_from8InParis() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(0L));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.just(0L));

        // 07:00 UTC is 08:00 in Paris in winter.
        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").catchUp()).expectComplete().verify(Duration.ofSeconds(10));
        verify(reminders).recordDueNotices();
        verify(friendshipReminders).recordDueNotices();
    }

    @Test
    void catchUp_shouldWaitFor8InParis_soThatARestartAtNightWakesNobody() {
        StepVerifier.create(jobAt("2027-03-12T06:59:00Z").catchUp()).expectComplete().verify(Duration.ofSeconds(10));
        verify(reminders, never()).recordDueNotices();
        verify(friendshipReminders, never()).recordDueNotices();
    }
}
