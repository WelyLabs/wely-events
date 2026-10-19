package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ParticipantsTest {

    private static Event organizedBy(String organizer, String... participants) {
        return Event.builder().id("1").organizerId(organizer).participantIds(new ArrayList<>(List.of(participants))).build();
    }

    @Test
    void seenBy_shouldListEveryone_whenTheViewerTakesPart() {
        Participants seen = Participants.seenBy(organizedBy("carol", "alice", "bob", "dave"), "bob", Set.of());

        assertThat(seen.count()).isEqualTo(3);
        assertThat(seen.participantIds()).containsExactly("alice", "bob", "dave");
    }

    @Test
    void seenBy_shouldListEveryone_whenTheOrganizerDoesNotTakePart() {
        Participants seen = Participants.seenBy(organizedBy("carol", "alice", "bob"), "carol", Set.of());

        assertThat(seen.participantIds()).containsExactly("alice", "bob");
    }

    @Test
    void seenBy_shouldOnlyCount_whenTheViewerIsNotInvolved() {
        Participants seen = Participants.seenBy(organizedBy("carol", "alice", "bob"), "erin", Set.of());

        assertThat(seen.count()).isEqualTo(2);
        assertThat(seen.participantIds()).isEmpty();
    }

    @Test
    void seenBy_shouldNameTheViewersFriendsAmongThem_inStoredOrder() {
        Participants seen = Participants.seenBy(organizedBy("carol", "dave", "alice", "bob"), "erin", Set.of("alice", "dave", "zoe"));

        assertThat(seen.friendsGoingIds()).containsExactly("dave", "alice");
    }

    @Test
    void seenBy_shouldNeverNameTheViewerAsAFriend() {
        Participants seen = Participants.seenBy(organizedBy("carol", "alice", "bob"), "bob", Set.of("alice", "bob"));

        assertThat(seen.friendsGoingIds()).containsExactly("alice");
    }

    @Test
    void seenBy_shouldBeEmpty_whenNobodyTakesPart() {
        Participants seen = Participants.seenBy(organizedBy("carol"), "carol", Set.of("alice"));

        assertThat(seen.count()).isZero();
        assertThat(seen.participantIds()).isEmpty();
        assertThat(seen.friendsGoingIds()).isEmpty();
    }

    private static Event inviting(String... invitees) {
        Event event = organizedBy("carol", "alice");
        event.setInvitedIds(new ArrayList<>(List.of(invitees)));
        return event;
    }

    @Test
    void seenBy_shouldListEveryInvitation_forTheOrganizer() {
        assertThat(Participants.seenBy(inviting("bob", "dave"), "carol", Set.of()).invitedIds()).containsExactly("bob", "dave");
    }

    @Test
    void seenBy_shouldListOnlyTheirOwnInvitation_forAnInvitee() {
        assertThat(Participants.seenBy(inviting("bob", "dave"), "bob", Set.of()).invitedIds()).containsExactly("bob");
    }

    @Test
    void seenBy_shouldListNoInvitation_forAParticipantOrAnyoneElse() {
        assertThat(Participants.seenBy(inviting("bob"), "alice", Set.of()).invitedIds()).isEmpty();
        assertThat(Participants.seenBy(inviting("bob"), "erin", Set.of()).invitedIds()).isEmpty();
    }

    @Test
    void seenBy_shouldListNoInvitation_onAnEventStoredBeforeThem() {
        Event event = organizedBy("carol");
        event.setInvitedIds(null);

        assertThat(Participants.seenBy(event, "carol", Set.of()).invitedIds()).isEmpty();
    }
}
