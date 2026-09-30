package com.calendar.events.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.config.PathMatchConfigurer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebConfigTest {

    private final WebConfig config = new WebConfig();

    @Test
    void configurePathMatching_shouldPrefixControllersWithTheServiceSegment() {
        PathMatchConfigurer configurer = mock(PathMatchConfigurer.class);

        config.configurePathMatching(configurer);

        // The gateway strips /api/v1 from /api/v1/events-service/** and leaves
        // /events-service: that prefix is reattached here.
        verify(configurer).addPathPrefix(eq("/events-service"), any(HandlerTypePredicate.class));
    }
}
