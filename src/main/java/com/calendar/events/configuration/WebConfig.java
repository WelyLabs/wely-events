package com.calendar.events.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * This service's additions to Boot's WebFlux configuration, which stays on.
 *
 * <p>No {@code @EnableWebFlux} here: it would replace Boot's WebFlux auto-configuration altogether, and every
 * {@code spring.webflux.*} property — the multipart limit among them — would be read and silently ignored.
 * Boot applies this configurer on top of its own.
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void configurePathMatching(PathMatchConfigurer configurer) {
        // Scoped to this service's own controllers by package, not by the @RestController
        // annotation: that predicate also matched springdoc's OpenApiWebfluxResource, which
        // moved the specification to /events-service/v3/api-docs and left /v3/api-docs a 404.
        configurer.addPathPrefix(
                "/events-service",
                HandlerTypePredicate.forBasePackage("com.calendar.events.application.rest"));
    }
}
