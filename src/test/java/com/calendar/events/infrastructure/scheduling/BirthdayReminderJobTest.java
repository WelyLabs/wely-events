package com.calendar.events.infrastructure.scheduling;

import com.calendar.events.domain.services.BirthdayReminderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BirthdayReminderJobTest {

    @Mock
    private BirthdayReminderService reminders;

    private BirthdayReminderJob jobAt(String instant) {
        return new BirthdayReminderJob(reminders, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void daily_shouldRecordTheDueNotices() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(3L));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).verifyComplete();
        verify(reminders).recordDueNotices();
    }

    @Test
    void daily_shouldSwallowAFailure_forTheNextRunToRetry() {
        when(reminders.recordDueNotices()).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).verifyComplete();
    }

    @Test
    void catchUp_shouldRun_from8InParis() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(0L));

        // 07:00 UTC is 08:00 in Paris in winter.
        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").catchUp()).verifyComplete();
        verify(reminders).recordDueNotices();
    }

    @Test
    void catchUp_shouldWaitFor8InParis_soThatARestartAtNightWakesNobody() {
        StepVerifier.create(jobAt("2027-03-12T06:59:00Z").catchUp()).verifyComplete();
        verify(reminders, never()).recordDueNotices();
    }
}
