package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ViewerFriendsTest {

    private static final Instant DAY = Instant.parse("2026-11-01T10:00:00Z");
    private static final ViewerFriends FRIENDS = new ViewerFriends(Map.of(
            "ana", Closeness.FRIEND, "bob", Closeness.CLOSE, "zoe", Closeness.INTIMATE, "max", Closeness.FRIEND,
            "eva", Closeness.CLOSE, "lou", Closeness.FRIEND));

    private static Event event(String organizer, String... participants) {
        return Event.builder().id(organizer).organizerId(organizer).startDate(DAY)
                .participantIds(new ArrayList<>(List.of(participants))).build();
    }

    @Test
    void friendIds_shouldListEveryFriend() {
        assertThat(FRIENDS.friendIds()).isEqualTo(Set.of("ana", "bob", "zoe", "max", "eva", "lou"));
        assertThat(ViewerFriends.NONE.friendIds()).isEmpty();
    }

    @Test
    void friendsGoingTo_shouldPutTheClosestFirst_theOrganizerThenJoinOrderWithinALevel_capped() {
        FriendsGoing going = FRIENDS.friendsGoingTo(event("max", "max", "ana", "stranger", "eva", "zoe", "lou", "bob"));

        assertThat(going.friends()).containsExactly(new Friend("zoe", Closeness.INTIMATE), new Friend("eva", Closeness.CLOSE),
                new Friend("bob", Closeness.CLOSE), new Friend("max", Closeness.FRIEND), new Friend("ana", Closeness.FRIEND));
        assertThat(going.count()).isEqualTo(6);
        assertThat(going.affinity()).isEqualTo(3);
    }

    @Test
    void friendsGoingTo_shouldCountTheOrganizerOnce_evenWhenAlsoAParticipant() {
        FriendsGoing going = FRIENDS.friendsGoingTo(event("bob", "bob"));

        assertThat(going.friends()).containsExactly(new Friend("bob", Closeness.CLOSE));
        assertThat(going.count()).isEqualTo(1);
        assertThat(going.affinity()).isEqualTo(2);
    }

    @Test
    void friendsGoingTo_shouldBeZero_withoutAFriendOnIt() {
        FriendsGoing going = FRIENDS.friendsGoingTo(event("stranger", "me"));

        assertThat(going.friends()).isEmpty();
        assertThat(going.count()).isZero();
        assertThat(going.affinity()).isZero();
    }

    @Test
    void friendsGoingTo_shouldHideThemOnABirthday() {
        Event birthday = event("bob");
        birthday.setKind(EventKind.BIRTHDAY);

        assertThat(FRIENDS.friendsGoingTo(birthday)).isEqualTo(FriendsGoing.HIDDEN);
    }

    @Test
    void friendsGoingTo_shouldCopeWithoutParticipants() {
        Event event = Event.builder().organizerId("ana").participantIds(null).build();

        assertThat(FRIENDS.friendsGoingTo(event).count()).isEqualTo(1);
    }

    @Test
    void byAffinity_shouldOrderByClosestFriendThenSoonest() {
        Event none = event("stranger");
        Event friendLater = event("ana");
        friendLater.setStartDate(DAY.plusSeconds(60));
        Event intimate = event("zoe");
        intimate.setStartDate(DAY.plusSeconds(120));
        Event friendSooner = event("max");
        Event birthday = event("bob");
        birthday.setKind(EventKind.BIRTHDAY);

        List<Event> sorted = new ArrayList<>(List.of(none, friendLater, intimate, friendSooner, birthday));
        sorted.sort(FRIENDS.byAffinity());

        assertThat(sorted).extracting(Event::getId).containsExactly("zoe", "max", "ana", "stranger", "bob");
    }
}
