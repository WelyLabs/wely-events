package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendshipHistoryEntryTest {

    private static final Instant AT = Instant.parse("2026-10-18T08:00:00Z");

    private static FriendshipHistoryEntry entry(FriendshipChangeType type, Closeness closeness, Closeness previous) {
        return new FriendshipHistoryEntry("alice:bob", "alice", "bob", type, closeness, previous, AT, 4);
    }

    @Test
    void of_shouldFlattenTheChange() {
        Friendship after = new Friendship("bob", "alice", true, Closeness.INTIMATE, AT, 4);

        assertThat(FriendshipHistoryEntry.of(new FriendshipChange(FriendshipChangeType.CLOSENESS_CHANGED, after,
                Closeness.CLOSE, AT)))
                .isEqualTo(entry(FriendshipChangeType.CLOSENESS_CHANGED, Closeness.INTIMATE, Closeness.CLOSE));
    }

    @Test
    void involves_shouldTellWhetherTheUserIsOneOfTheTwo() {
        FriendshipHistoryEntry entry = entry(FriendshipChangeType.ACCEPTED, Closeness.FRIEND, null);

        assertThat(entry.involves("alice")).isTrue();
        assertThat(entry.involves("bob")).isTrue();
        assertThat(entry.involves("zoe")).isFalse();
    }

    @Test
    void otherThan_shouldNameTheOtherSide_andRefuseAStranger() {
        FriendshipHistoryEntry entry = entry(FriendshipChangeType.ACCEPTED, Closeness.FRIEND, null);

        assertThat(entry.otherThan("alice")).isEqualTo("bob");
        assertThat(entry.otherThan("bob")).isEqualTo("alice");
        assertThatThrownBy(() -> entry.otherThan("zoe")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isUpgrade_shouldHoldOnlyForAClosenessChangeToACloserLevel() {
        assertThat(entry(FriendshipChangeType.CLOSENESS_CHANGED, Closeness.INTIMATE, Closeness.CLOSE).isUpgrade()).isTrue();
        assertThat(entry(FriendshipChangeType.CLOSENESS_CHANGED, Closeness.CLOSE, Closeness.FRIEND).isUpgrade()).isTrue();
        assertThat(entry(FriendshipChangeType.CLOSENESS_CHANGED, Closeness.FRIEND, Closeness.CLOSE).isUpgrade()).isFalse();
        assertThat(entry(FriendshipChangeType.CLOSENESS_CHANGED, Closeness.CLOSE, null).isUpgrade()).isFalse();
        assertThat(entry(FriendshipChangeType.SNAPSHOT, Closeness.INTIMATE, Closeness.FRIEND).isUpgrade()).isFalse();
        assertThat(entry(FriendshipChangeType.REMOVED, null, Closeness.FRIEND).isUpgrade()).isFalse();
    }
}
