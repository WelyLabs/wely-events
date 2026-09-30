package com.calendar.events;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Spring context actually starts.
 *
 * <p>This test was commented out, which meant nothing checked that the beans wire
 * together: a missing property or a broken bean definition surfaced only at deploy
 * time. It needs {@code application-test.properties} to stand in for the values the
 * environment injects in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class EventsApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    void contextShouldExposeTheDomainServiceAndItsAdapter() {
        assertThat(context.getBean(com.calendar.events.domain.services.EventService.class)).isNotNull();
        assertThat(context.getBean(com.calendar.events.domain.ports.EventRepository.class)).isNotNull();
    }
}
