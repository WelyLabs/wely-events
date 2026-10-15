package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EventTest {

    private static final Instant START = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void effectiveEnd_shouldBeTheEndWhenThereIsOne() {
        Event event = Event.builder().startDate(START).endDate(START.plusSeconds(7200)).build();

        assertThat(event.effectiveEnd()).isEqualTo(START.plusSeconds(7200));
    }

    @Test
    void effectiveEnd_shouldDefaultToOneHour_whenTheEventHasNoEnd() {
        Event event = Event.builder().startDate(START).build();

        assertThat(event.effectiveEnd()).isEqualTo(START.plus(Event.DEFAULT_DURATION));
    }

    private static Event by(String organizer, Visibility visibility, String... participants) {
        return Event.builder().organizerId(organizer).visibility(visibility)
                .participantIds(new ArrayList<>(List.of(participants))).build();
    }

    @Test
    void effectiveVisibility_shouldTreatAMissingVisibilityAsFriends() {
        assertThat(by("bob", null).effectiveVisibility()).isEqualTo(Visibility.FRIENDS);
        assertThat(by("bob", Visibility.PRIVATE).effectiveVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void involves_shouldCoverTheOrganizerAndTheParticipants() {
        Event event = by("bob", Visibility.FRIENDS, "lea");

        assertThat(event.involves("bob")).isTrue();
        assertThat(event.involves("lea")).isTrue();
        assertThat(event.involves("carol")).isFalse();
    }

    @Test
    void isVisibleTo_shouldAlwaysShowAnEventToThoseTakingPart() {
        assertThat(by("bob", Visibility.PRIVATE, "lea").isVisibleTo("lea", Set.of())).isTrue();
    }

    @Test
    void isVisibleTo_shouldShowAPublicEventToAnyone() {
        assertThat(by("bob", Visibility.PUBLIC).isVisibleTo("carol", Set.of())).isTrue();
    }

    @Test
    void isVisibleTo_shouldShowAFriendsEventOnlyToTheOrganizersFriends() {
        Event event = by("bob", Visibility.FRIENDS);

        assertThat(event.isVisibleTo("lea", Set.of("bob"))).isTrue();
        assertThat(event.isVisibleTo("carol", Set.of("lea"))).isFalse();
    }

    @Test
    void isVisibleTo_shouldHideAPrivateEventFromFriends() {
        assertThat(by("bob", Visibility.PRIVATE).isVisibleTo("lea", Set.of("bob"))).isFalse();
    }

    @Test
    void isPolling_shouldHoldOnlyWhileThePollIsOpen() {
        assertThat(Event.builder().build().isPolling()).isFalse();
        assertThat(Event.builder().poll(new Poll(PollStatus.OPEN, List.of(), null, null)).build().isPolling()).isTrue();
        assertThat(Event.builder().poll(new Poll(PollStatus.CLOSED, List.of(), null, "s1")).build().isPolling()).isFalse();
    }

    @Test
    void hasOtherParticipants_shouldIgnoreTheOrganizer() {
        assertThat(Event.builder().organizerId("org").participantIds(List.of("org")).build().hasOtherParticipants()).isFalse();
        assertThat(Event.builder().organizerId("org").build().hasOtherParticipants()).isFalse();
        assertThat(Event.builder().organizerId("org").participantIds(List.of("org", "lea")).build().hasOtherParticipants()).isTrue();
    }
}
