package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackedPairTest {

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");
    private static final Friendship FRIENDS_AT_5 = new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 5);

    @Test
    void endedByTombstone_shouldBeTrue_whenTheLastStreamedValueIsTheStoredOne() {
        assertThat(new TrackedPair(FRIENDS_AT_5, 5L).endedByTombstone()).isTrue();
    }

    @Test
    void endedByTombstone_shouldBeTrue_forAPairStoredBeforeTheStateTopicWasRead() {
        assertThat(new TrackedPair(FRIENDS_AT_5, null).endedByTombstone()).isTrue();
    }

    @Test
    void endedByTombstone_shouldBeFalse_whenTheLastStreamedValueWasStale() {
        assertThat(new TrackedPair(FRIENDS_AT_5, 3L).endedByTombstone()).isFalse();
    }

    @Test
    void endedByTombstone_shouldBeFalse_whenThePairIsNotFriends() {
        assertThat(new TrackedPair(new Friendship("alice", "bob", false, null, null, 5), 5L).endedByTombstone()).isFalse();
    }

    @Test
    void trackedPair_shouldNeedAPair() {
        assertThatThrownBy(() -> new TrackedPair(null, 1L)).isInstanceOf(NullPointerException.class);
    }
}
