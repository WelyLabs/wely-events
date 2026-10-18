package com.calendar.events.domain.models;

import java.util.Locale;
import java.util.Optional;

/** How the feed is ordered. */
public enum FeedRank {
    /** As stored: the order the feed always had. */
    DEFAULT,
    /** The events the viewer's closest friends go to first, then the soonest. */
    AFFINITY;

    /** The rank a client names, in any case; DEFAULT for none, empty for an unknown one. */
    public static Optional<FeedRank> named(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of(DEFAULT);
        }
        try {
            return Optional.of(valueOf(name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
