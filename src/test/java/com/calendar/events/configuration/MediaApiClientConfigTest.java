package com.calendar.events.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaApiClientConfigTest {

    @Test
    void mediaWebClient_shouldBeBuiltForTheConfiguredUrl() {
        // The URL, the secret header and the timeout are exercised end to end by MediaApiAdapterTest.
        assertThat(new MediaApiClientConfig().mediaWebClient("http://media:8085", "secret")).isNotNull();
        assertThat(MediaApiClientConfig.TIMEOUT).hasSeconds(10);
    }
}
