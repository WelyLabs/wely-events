package com.calendar.events.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled}, for the birthday reminders; off with them, as in the test profile. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.birthday-reminders", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
