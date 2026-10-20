package com.calendar.events.configuration;

import com.calendar.events.application.rest.EventController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.multipart.DefaultPartHttpMessageReader;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.config.PathMatchConfigurer;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebConfigTest {

    private final WebConfig config = new WebConfig();

    @Test
    @DisplayName("controllers are prefixed with the service segment")
    void configurePathMatching_shouldPrefixControllersWithTheServiceSegment() {
        PathMatchConfigurer configurer = mock(PathMatchConfigurer.class);

        config.configurePathMatching(configurer);

        // The gateway strips /api/v1 from /api/v1/events-service/** and leaves
        // /events-service: that prefix is reattached here.
        verify(configurer).addPathPrefix(eq("/events-service"), any(HandlerTypePredicate.class));
    }

    @Test
    @DisplayName("the prefix applies to this service's own controllers only")
    void configurePathMatching_shouldTargetThisServicesControllersOnly() {
        PathMatchConfigurer configurer = mock(PathMatchConfigurer.class);
        ArgumentCaptor<HandlerTypePredicate> predicate =
                ArgumentCaptor.forClass(HandlerTypePredicate.class);

        config.configurePathMatching(configurer);
        verify(configurer).addPathPrefix(eq("/events-service"), predicate.capture());

        assertThat(predicate.getValue().test(EventController.class)).isTrue();

        // Selecting on the @RestController annotation instead would also match springdoc's
        // OpenApiWebfluxResource, which served the specification at
        // /events-service/v3/api-docs — behind authentication — and left /v3/api-docs a 404.
        // ForeignController stands in for it: annotated, but not ours.
        assertThat(predicate.getValue().test(ForeignController.class)).isFalse();
        assertThat(predicate.getValue().test(PlainClass.class)).isFalse();
    }

    @RestController
    private static class ForeignController { }

    private static class PlainClass { }
    /**
     * Boot's WebFlux auto-configuration, which {@code @EnableWebFlux} used to switch off. With it off, the
     * properties below were read and ignored: the multipart limit stayed at the framework's 256 KB.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    class InTheApplication {

        @Autowired
        private ApplicationContext context;

        @Test
        @DisplayName("spring.webflux.multipart.max-in-memory-size reaches the multipart reader")
        void webFluxProperties_shouldTakeEffect() {
            long configured = DataSize.parse(context.getEnvironment()
                    .getRequiredProperty("spring.webflux.multipart.max-in-memory-size")).toBytes();

            assertThat(context.getBean(ServerCodecConfigurer.class).getReaders())
                    .filteredOn(DefaultPartHttpMessageReader.class::isInstance)
                    .singleElement()
                    .extracting(reader -> (long) ((DefaultPartHttpMessageReader) reader).getMaxInMemorySize())
                    .isEqualTo(configured)
                    .isNotEqualTo(256L * 1024);
        }

        @Test
        @DisplayName("Boot's ProblemDetailsExceptionHandler stays out: GlobalErrorHandler answers every error")
        void problemDetailsExceptionHandler_shouldNotBeRegistered() {
            assertThat(context.getBeanNamesForType(ResponseEntityExceptionHandler.class)).isEmpty();
            assertThat(context.getEnvironment().getProperty("spring.webflux.problemdetails.enabled")).isNull();
        }

        @Test
        @DisplayName("this configurer is still applied on top of Boot's")
        void configurePathMatching_shouldStillPrefixTheControllers() {
            assertThat(context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods().keySet())
                    .anyMatch(info -> info.getPatternsCondition().getPatterns().stream()
                            .anyMatch(pattern -> pattern.getPatternString().equals("/events-service/events/me/feed")));
        }
    }
}
