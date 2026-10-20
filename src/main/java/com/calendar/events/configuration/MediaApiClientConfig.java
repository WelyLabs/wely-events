package com.calendar.events.configuration;

import io.netty.channel.ChannelOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/** Client for wely-media, authenticated by the shared internal secret. */
@Configuration
public class MediaApiClientConfig {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    public static final String SECRET_HEADER = "X-Internal-Secret";

    @Bean
    public WebClient mediaWebClient(@Value("${app.media-api.url}") String mediaApiUrl,
                                    @Value("${app.media-api.secret}") String secret) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TIMEOUT.toMillis())
                .responseTimeout(TIMEOUT);
        return WebClient.builder()
                .baseUrl(mediaApiUrl)
                .defaultHeader(SECRET_HEADER, secret)
                .filter(new RequestIdExchangeFilterFunction())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
