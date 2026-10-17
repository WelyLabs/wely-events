package com.calendar.events.infrastructure.scheduling;

import com.calendar.events.domain.services.BirthdayReminderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Runs the birthday reminders: every day at 08:00 in Paris, and hourly from shortly after startup
 * to catch up a missed or failed run. Notices are keyed, so a repeated run, or every pod running
 * it, records nothing twice. The catch-up waits for 08:00, so a restart at night wakes nobody.
 * Spring subscribes to the returned Mono itself.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.birthday-reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BirthdayReminderJob {

    static final String ZONE = "Europe/Paris";
    static final LocalTime SENDING_FROM = LocalTime.of(8, 0);

    private final BirthdayReminderService reminders;
    private final Clock clock;

    public BirthdayReminderJob(BirthdayReminderService reminders, Clock clock) {
        this.reminders = reminders;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.birthday-reminders.cron:0 0 8 * * *}", zone = ZONE)
    public Mono<Void> daily() {
        return run("daily");
    }

    @Scheduled(initialDelayString = "${app.birthday-reminders.catch-up-delay:PT1M}",
            fixedDelayString = "${app.birthday-reminders.catch-up-interval:PT1H}")
    public Mono<Void> catchUp() {
        if (clock.instant().atZone(ZoneId.of(ZONE)).toLocalTime().isBefore(SENDING_FROM)) {
            return Mono.empty();
        }
        return run("catch-up");
    }

    private Mono<Void> run(String which) {
        return Mono.defer(reminders::recordDueNotices)
                .doOnNext(recorded -> {
                    if (recorded > 0) {
                        log.info("Birthday reminders ({}): {} notice(s) recorded", which, recorded);
                    }
                })
                .onErrorResume(error -> {
                    log.warn("Birthday reminders ({}) failed; the next run retries: {}", which, error.toString());
                    return Mono.empty();
                })
                .then();
    }
}
