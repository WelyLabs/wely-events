package com.calendar.events.configuration;

import com.calendar.events.domain.services.AccountGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Deny by default: every exchange but the CORS preflight needs a valid JWT, and a deleted account cannot write. */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http, AccountGuard accountGuard,
                                                           ServerCodecConfigurer codecs, MeterRegistry meterRegistry) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Kubernetes probes: the kubelet carries no token. Only the two
                        // health groups are opened, not /actuator as a whole.
                        .pathMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        // OpenAPI. These paths are not routed by the gateway — it forwards
                        // /api/v1/<service>/** and /rsocket only — so opening them here makes
                        // the documentation reachable in-cluster and in dev, and nowhere else.
                        // A calendar app opens the .ics without the viewer's token: the unguessable,
                        // short-lived link it carries stands for the check made when it was issued.
                        .pathMatchers(HttpMethod.GET, "/events-service/public/calendar/*").permitAll()
                        .pathMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**",
                                      "/swagger-ui.html", "/webjars/swagger-ui/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterAfter(new DeletedRequesterFilter(accountGuard, codecs, meterRegistry), SecurityWebFiltersOrder.AUTHORIZATION)
                .build();
    }
}
