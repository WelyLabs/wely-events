package com.calendar.events.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Client for wely-social. Spring Boot 4 no longer auto-configures a WebClient.Builder. */
@Configuration
public class SocialClientConfig {

    @Bean
    public WebClient socialWebClient(@Value("${social.api.url}") String socialApiUrl) {
        return WebClient.builder().baseUrl(socialApiUrl).build();
    }
}
