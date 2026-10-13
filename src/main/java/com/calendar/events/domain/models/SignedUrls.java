package com.calendar.events.domain.models;

import java.util.Map;
import java.util.stream.Collectors;

/** Short-lived URLs of stored pictures, by key; a key without one (unknown, or signing failed) gives null. */
public record SignedUrls(Map<String, String> byKey) {

    public static final SignedUrls NONE = new SignedUrls(Map.of());

    public SignedUrls {
        byKey = byKey.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public String urlOf(String key) {
        return key == null ? null : byKey.get(key);
    }
}
