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
    void isVisibleTo_shouldHideACommunityEventFromAnyoneNotTakingPart_membershipBeingDecidedElsewhere() {
        Event event = by("bob", Visibility.COMMUNITY, "lea");
        event.setCommunityId("c1");

        assertThat(event.isVisibleTo("carol", Set.of("bob"))).isFalse();
        assertThat(event.isVisibleTo("lea", Set.of())).isTrue();
    }

    @Test
    void isCommunityEvent_shouldFollowTheCommunity() {
        Event event = by("bob", Visibility.FRIENDS);
        assertThat(event.isCommunityEvent()).isFalse();

        event.setCommunityId("c1");
        assertThat(event.isCommunityEvent()).isTrue();
    }

    @Test
    void isPolling_shouldHoldOnlyWhileThePollIsOpen() {
        assertThat(Event.builder().build().isPolling()).isFalse();
        assertThat(Event.builder().poll(new Poll(PollStatus.OPEN, List.of(), null, null)).build().isPolling()).isTrue();
        assertThat(Event.builder().poll(new Poll(PollStatus.CLOSED, List.of(), null, "s1")).build().isPolling()).isFalse();
    }

    @Test
    void isOverAt_shouldBeTrue_whenTheEventEndedBefore() {
        Event event = Event.builder().startDate(START).endDate(START.plusSeconds(7200)).build();

        assertThat(event.isOverAt(START.plusSeconds(7201))).isTrue();
    }

    @Test
    void isOverAt_shouldBeFalse_whenTheEventEndsExactlyThen() {
        Event event = Event.builder().startDate(START).endDate(START.plusSeconds(7200)).build();

        assertThat(event.isOverAt(START.plusSeconds(7200))).isFalse();
        assertThat(event.isOverAt(START)).isFalse();
    }

    @Test
    void isOverAt_shouldUseTheDefaultDuration_whenTheEventHasNoEnd() {
        Event event = Event.builder().startDate(START).build();

        assertThat(event.isOverAt(START.plus(Event.DEFAULT_DURATION))).isFalse();
        assertThat(event.isOverAt(START.plus(Event.DEFAULT_DURATION).plusSeconds(1))).isTrue();
    }

    @Test
    void isOverAt_shouldBeFalse_whileAPollIsOpen() {
        Event open = Event.builder().startDate(START).endDate(START.plusSeconds(3600))
                .poll(new Poll(PollStatus.OPEN, List.of(), null, null)).build();
        Event fixed = Event.builder().startDate(START).endDate(START.plusSeconds(3600))
                .poll(new Poll(PollStatus.CLOSED, List.of(), null, "s1")).build();

        assertThat(open.isOverAt(START.plusSeconds(86_400))).isFalse();
        assertThat(fixed.isOverAt(START.plusSeconds(86_400))).isTrue();
    }

    @Test
    void successorExcluding_shouldReturnTheEarliestParticipantOtherThanTheOrganizer() {
        Event event = by("org", Visibility.FRIENDS, "org", "lea", "hugo");

        assertThat(event.successorExcluding(Set.of())).contains("lea");
    }

    @Test
    void successorExcluding_shouldSkipExcludedIds() {
        Event event = by("org", Visibility.FRIENDS, "lea", "org", "hugo", "zoe");

        assertThat(event.successorExcluding(Set.of("lea", "hugo"))).contains("zoe");
    }

    @Test
    void successorExcluding_shouldBeEmpty_whenOnlyTheOrganizerOrExcludedIdsRemain() {
        assertThat(by("org", Visibility.FRIENDS, "org", "lea").successorExcluding(Set.of("lea"))).isEmpty();
        assertThat(by("org", Visibility.FRIENDS, "org").successorExcluding(Set.of())).isEmpty();
        assertThat(by("org", Visibility.FRIENDS).successorExcluding(Set.of())).isEmpty();
    }
}
