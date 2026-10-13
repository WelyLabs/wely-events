package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SignedUrlsTest {

    @Test
    void urlOf_shouldGiveTheUrlOfAKnownKey() {
        assertThat(new SignedUrls(Map.of("k", "https://u")).urlOf("k")).isEqualTo("https://u");
    }

    @Test
    void urlOf_shouldGiveNull_forAnUnknownOrNullKey() {
        SignedUrls urls = new SignedUrls(Map.of("k", "https://u"));

        assertThat(urls.urlOf("other")).isNull();
        assertThat(urls.urlOf(null)).isNull();
    }

    @Test
    void constructor_shouldDropEntriesWithoutUrl() {
        Map<String, String> answered = new HashMap<>();
        answered.put("k", null);
        answered.put("j", "https://j");

        assertThat(new SignedUrls(answered).byKey()).containsOnlyKeys("j");
    }
}
