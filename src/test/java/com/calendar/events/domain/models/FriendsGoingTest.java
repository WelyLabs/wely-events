package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendsGoingTest {

    @Test
    void affinityOf_shouldGoFromZeroToThree() {
        assertThat(FriendsGoing.affinityOf(null)).isZero();
        assertThat(FriendsGoing.affinityOf(Closeness.FRIEND)).isEqualTo(1);
        assertThat(FriendsGoing.affinityOf(Closeness.CLOSE)).isEqualTo(2);
        assertThat(FriendsGoing.affinityOf(Closeness.INTIMATE)).isEqualTo(3);
    }

    @Test
    void constructor_shouldCopyTheFriends_andKeepHiddenOnesNull() {
        List<Friend> friends = new ArrayList<>(List.of(new Friend("ana", Closeness.FRIEND)));
        FriendsGoing going = new FriendsGoing(friends, 1, 1);
        friends.clear();

        assertThat(going.friends()).hasSize(1);
        assertThatThrownBy(() -> going.friends().add(new Friend("bob", Closeness.CLOSE)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(FriendsGoing.HIDDEN.friends()).isNull();
        assertThat(FriendsGoing.HIDDEN.count()).isNull();
        assertThat(FriendsGoing.HIDDEN.affinity()).isNull();
    }
}
