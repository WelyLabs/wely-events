package com.calendar.events.infrastructure.scheduling;

import com.calendar.events.domain.services.BirthdayReminderService;
import com.calendar.events.domain.services.FriendshipReminderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BirthdayReminderJobTest {

    @Mock
    private BirthdayReminderService reminders;
    @Mock
    private FriendshipReminderService friendshipReminders;

    private BirthdayReminderJob jobAt(String instant) {
        return new BirthdayReminderJob(reminders, friendshipReminders, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void daily_shouldRecordTheBirthdayNotices_thenTheFriendshipOnes() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(3L));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.just(2L));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).verifyComplete();
        InOrder order = inOrder(reminders, friendshipReminders);
        order.verify(reminders).recordDueNotices();
        order.verify(friendshipReminders).recordDueNotices();
    }

    @Test
    void daily_shouldSwallowAFailure_forTheNextRunToRetry_andStillRunTheFriendshipNotices() {
        when(reminders.recordDueNotices()).thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.just(0L));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).verifyComplete();
        verify(friendshipReminders).recordDueNotices();
    }

    @Test
    void daily_shouldSwallowAFriendshipNoticesFailure() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(0L));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").daily()).verifyComplete();
    }

    @Test
    void catchUp_shouldRun_from8InParis() {
        when(reminders.recordDueNotices()).thenReturn(Mono.just(0L));
        when(friendshipReminders.recordDueNotices()).thenReturn(Mono.just(0L));

        // 07:00 UTC is 08:00 in Paris in winter.
        StepVerifier.create(jobAt("2027-03-12T07:00:00Z").catchUp()).verifyComplete();
        verify(reminders).recordDueNotices();
        verify(friendshipReminders).recordDueNotices();
    }

    @Test
    void catchUp_shouldWaitFor8InParis_soThatARestartAtNightWakesNobody() {
        StepVerifier.create(jobAt("2027-03-12T06:59:00Z").catchUp()).verifyComplete();
        verify(reminders, never()).recordDueNotices();
        verify(friendshipReminders, never()).recordDueNotices();
    }
}
