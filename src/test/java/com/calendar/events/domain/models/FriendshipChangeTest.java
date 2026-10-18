package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendshipChangeTest {

    private static final Friendship PAIR =
            new Friendship("alice", "bob", true, Closeness.CLOSE, Instant.parse("2026-05-01T10:00:00Z"), 2);
    private static final Instant AT = Instant.parse("2026-10-18T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");

    @Test
    void constructor_shouldRequireATypeAndThePair() {
        assertThatThrownBy(() -> new FriendshipChange(null, PAIR, null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FriendshipChange(FriendshipChangeType.ACCEPTED, null, null, AT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void datedOrAt_shouldKeepTheRecordsDate() {
        FriendshipChange change = new FriendshipChange(FriendshipChangeType.ACCEPTED, PAIR, null, AT);

        assertThat(change.datedOrAt(NOW)).isSameAs(change);
    }

    @Test
    void datedOrAt_shouldDateAnUndatedChange() {
        FriendshipChange change = new FriendshipChange(FriendshipChangeType.ACCEPTED, PAIR, Closeness.FRIEND, null);

        assertThat(change.datedOrAt(NOW))
                .isEqualTo(new FriendshipChange(FriendshipChangeType.ACCEPTED, PAIR, Closeness.FRIEND, NOW));
    }
}
