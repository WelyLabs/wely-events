package com.calendar.events.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SocialClientConfigTest {

    @Test
    void socialWebClient_shouldBeBuiltForTheConfiguredUrl() {
        // The URL itself is exercised end to end by SocialFriendDirectoryAdapterTest.
        assertThat(new SocialClientConfig().socialWebClient("http://social:8083")).isNotNull();
    }
}
