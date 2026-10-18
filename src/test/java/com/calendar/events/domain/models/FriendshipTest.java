package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendshipTest {

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");

    @Test
    void constructor_shouldSortThePair_soEitherSideGivesTheSameOne() {
        Friendship fromBob = new Friendship("bob", "alice", true, Closeness.CLOSE, SINCE, 3);
        Friendship fromAlice = new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 3);

        assertThat(fromBob).isEqualTo(fromAlice);
        assertThat(fromBob.userA()).isEqualTo("alice");
        assertThat(fromBob.userB()).isEqualTo("bob");
    }

    @Test
    void constructor_shouldDropClosenessAndSince_whenTheyAreNotFriends() {
        Friendship parted = new Friendship("alice", "bob", false, Closeness.CLOSE, SINCE, 4);

        assertThat(parted.closeness()).isNull();
        assertThat(parted.since()).isNull();
    }

    @Test
    void constructor_shouldRefuseOneUserTwice_friendsWithoutACloseness_andAMissingId() {
        assertThatThrownBy(() -> new Friendship("alice", "alice", true, Closeness.FRIEND, SINCE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Friendship("alice", "bob", true, null, SINCE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Friendship(null, "bob", false, null, null, 1))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void after_shouldMakeFriendsOnAnAcceptance_andStrangersOnARemoval() {
        assertThat(Friendship.after(FriendshipChangeType.ACCEPTED, "bob", "alice", Closeness.FRIEND, SINCE, 5))
                .isEqualTo(new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 5));
        assertThat(Friendship.after(FriendshipChangeType.REMOVED, "bob", "alice", null, null, 6))
                .isEqualTo(new Friendship("alice", "bob", false, null, null, 6));
    }

    @Test
    void after_shouldRefuseAChangeLeavingFriendsWithoutACloseness() {
        assertThatThrownBy(() -> Friendship.after(FriendshipChangeType.CLOSENESS_CHANGED, "bob", "alice", null, SINCE, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pairId_shouldJoinTheSortedIds() {
        assertThat(new Friendship("bob", "alice", false, null, null, 1).pairId()).isEqualTo("alice:bob");
    }

    @Test
    void friendOf_shouldNameTheOtherSide_fromEitherSide() {
        Friendship friendship = new Friendship("alice", "bob", true, Closeness.INTIMATE, SINCE, 2);

        assertThat(friendship.friendOf("alice")).isEqualTo(new Friend("bob", Closeness.INTIMATE));
        assertThat(friendship.friendOf("bob")).isEqualTo(new Friend("alice", Closeness.INTIMATE));
    }

    @Test
    void friendOf_shouldRefuseSomeoneOutsideThePair() {
        Friendship friendship = new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 2);

        assertThatThrownBy(() -> friendship.friendOf("zoe")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pairIdOf_shouldGiveTheSameIdInEitherOrder() {
        assertThat(Friendship.pairIdOf("bob", "alice")).isEqualTo("alice:bob");
        assertThat(Friendship.pairIdOf("alice", "bob")).isEqualTo("alice:bob");
        assertThat(Friendship.pairIdOf("bob", "alice"))
                .isEqualTo(new Friendship("bob", "alice", true, Closeness.FRIEND, SINCE, 1).pairId());
    }

    @Test
    void involves_shouldTellWhetherTheUserIsOneOfTheTwo() {
        Friendship pair = new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 1);

        assertThat(pair.involves("alice")).isTrue();
        assertThat(pair.involves("bob")).isTrue();
        assertThat(pair.involves("zoe")).isFalse();
    }

    @Test
    void otherThan_shouldNameTheOtherSide_andRefuseAStranger() {
        Friendship pair = new Friendship("alice", "bob", true, Closeness.FRIEND, SINCE, 1);

        assertThat(pair.otherThan("alice")).isEqualTo("bob");
        assertThat(pair.otherThan("bob")).isEqualTo("alice");
        assertThatThrownBy(() -> pair.otherThan("zoe")).isInstanceOf(IllegalArgumentException.class);
    }
}
