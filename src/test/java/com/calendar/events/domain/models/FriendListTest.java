package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendListTest {

    private static final Instant EARLY = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant LATE = Instant.parse("2026-10-10T10:00:00Z");

    @Test
    void friendList_shouldKeepACopyOfTheFriends() {
        Map<String, Closeness> friends = new HashMap<>(Map.of("bob", Closeness.CLOSE));
        FriendList list = new FriendList("lea", friends, EARLY);
        friends.put("hugo", Closeness.FRIEND);

        assertThat(list.friends()).containsOnlyKeys("bob");
        assertThatThrownBy(() -> list.friends().put("zoe", Closeness.FRIEND)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void friendsOf_shouldMergeTheUsersOwnListWithEveryListNamingThem() {
        FriendList own = new FriendList("lea", Map.of("bob", Closeness.CLOSE), EARLY);
        FriendList hugos = new FriendList("hugo", Map.of("lea", Closeness.INTIMATE), LATE);

        assertThat(FriendList.friendsOf("lea", own, List.of(hugos)))
                .isEqualTo(Map.of("bob", Closeness.CLOSE, "hugo", Closeness.INTIMATE));
    }

    @Test
    void friendsOf_shouldLetTheFresherListDecide_whenTheyDisagree() {
        // Bob made Léa intimate after her list was read: his newer list wins.
        FriendList own = new FriendList("lea", Map.of("bob", Closeness.FRIEND), EARLY);
        FriendList bobs = new FriendList("bob", Map.of("lea", Closeness.INTIMATE), LATE);
        assertThat(FriendList.friendsOf("lea", own, List.of(bobs))).isEqualTo(Map.of("bob", Closeness.INTIMATE));

        // Léa unfriended Bob after his list was read: her newer list, without him, wins.
        FriendList fresherOwn = new FriendList("lea", Map.of(), LATE);
        FriendList staleBobs = new FriendList("bob", Map.of("lea", Closeness.CLOSE), EARLY);
        assertThat(FriendList.friendsOf("lea", fresherOwn, List.of(staleBobs))).isEmpty();
    }

    @Test
    void friendsOf_shouldWorkWithoutAListOfTheUsersOwn() {
        FriendList bobs = new FriendList("bob", Map.of("lea", Closeness.CLOSE), EARLY);

        assertThat(FriendList.friendsOf("lea", null, List.of(bobs))).isEqualTo(Map.of("bob", Closeness.CLOSE));
    }

    @Test
    void friendsOf_shouldDropAFresherListThatNoLongerNamesTheUser_andIgnoreTheUsersOwnAmongThem() {
        FriendList own = new FriendList("lea", Map.of("bob", Closeness.CLOSE), EARLY);
        FriendList bobsWithoutLea = new FriendList("bob", Map.of(), LATE);

        assertThat(FriendList.friendsOf("lea", own, List.of(bobsWithoutLea, own))).isEmpty();
    }
}
