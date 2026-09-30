package com.calendar.events.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Deny by default: every exchange but the CORS preflight requires a valid JWT.
     *
     * <p>The gateway already authenticates incoming traffic, but this service must
     * not rely on that alone — it is reachable from inside the cluster, so it
     * enforces the same contract independently.
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Probes Kubernetes : le kubelet n'a pas de token. Seuls les deux
                        // groupes de santé sont ouverts, pas /actuator dans son ensemble.
                        .pathMatchers("/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
