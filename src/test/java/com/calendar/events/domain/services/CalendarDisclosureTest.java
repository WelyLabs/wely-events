package com.calendar.events.domain.services;

import com.calendar.events.domain.models.CalendarEntry;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Alice looks at Bob's calendar. Carol is someone Alice does not know. */
class CalendarDisclosureTest {

    private static final Instant START = Instant.parse("2026-10-17T18:00:00Z");
    private static final Instant END = Instant.parse("2026-10-17T20:00:00Z");
    private static final Set<String> ALICE_FRIENDS = Set.of("bob");

    private static Event event(String organizer, Visibility visibility, String... participants) {
        return Event.builder().id("e1").title("Dinner").location("Lyon").description("At 8")
                .organizerId(organizer).visibility(visibility).startDate(START).endDate(END)
                .participantIds(new ArrayList<>(List.of(participants))).build();
    }

    private static final CalendarEntry FULL = new CalendarEntry(START, END, "e1", "Dinner", "Lyon", "At 8");
    private static final CalendarEntry TITLE = new CalendarEntry(START, END, null, "Dinner", null, null);
    private static final CalendarEntry BUSY = CalendarEntry.busy(START, END);

    private static CalendarEntry seenBy(Event event, Closeness closeness) {
        return CalendarDisclosure.disclose(event, "alice", ALICE_FRIENDS, closeness);
    }

    @ParameterizedTest
    @EnumSource(Closeness.class)
    void disclose_shouldShowInFullWhatAliceMaySeeAnyway(Closeness closeness) {
        assertThat(seenBy(event("bob", Visibility.FRIENDS), closeness)).isEqualTo(FULL);
        assertThat(seenBy(event("carol", Visibility.PUBLIC, "bob"), closeness)).isEqualTo(FULL);
        assertThat(seenBy(event("carol", Visibility.PRIVATE, "bob", "alice"), closeness)).isEqualTo(FULL);
    }

    @ParameterizedTest
    @EnumSource(Closeness.class)
    void disclose_shouldOnlyShowAPrivateEventAsBusy(Closeness closeness) {
        assertThat(seenBy(event("bob", Visibility.PRIVATE), closeness)).isEqualTo(BUSY);
        assertThat(seenBy(event("carol", Visibility.PRIVATE, "bob"), closeness)).isEqualTo(BUSY);
    }

    @ParameterizedTest
    @EnumSource(Closeness.class)
    void disclose_shouldOnlyShowACommunityEventAsBusy_evenToAnIntimateFriend(Closeness closeness) {
        // The community's events are its members' business: closeness to the friend says nothing about them.
        assertThat(seenBy(event("bob", Visibility.COMMUNITY), closeness)).isEqualTo(BUSY);
        assertThat(seenBy(event("carol", Visibility.COMMUNITY, "bob"), closeness)).isEqualTo(BUSY);
    }

    @Test
    void disclose_shouldShowAStrangersEventAsBusy_toAFriend() {
        assertThat(seenBy(event("carol", Visibility.FRIENDS, "bob"), Closeness.FRIEND)).isEqualTo(BUSY);
    }

    @Test
    void disclose_shouldShowAStrangersEventTitle_toACloseFriend() {
        assertThat(seenBy(event("carol", Visibility.FRIENDS, "bob"), Closeness.CLOSE)).isEqualTo(TITLE);
    }

    @Test
    void disclose_shouldShowAStrangersEventInFull_toAnIntimateFriend() {
        assertThat(seenBy(event("carol", Visibility.FRIENDS, "bob"), Closeness.INTIMATE)).isEqualTo(FULL);
    }

    @Test
    void disclose_shouldTreatAnEventWithoutVisibilityAsFriendsOnly() {
        assertThat(seenBy(event("carol", null, "bob"), Closeness.FRIEND)).isEqualTo(BUSY);
    }

    @Test
    void disclose_shouldGiveAnEventWithoutAnEndTheDefaultHour() {
        Event open = event("carol", Visibility.PRIVATE, "bob");
        open.setEndDate(null);

        assertThat(seenBy(open, Closeness.FRIEND).end()).isEqualTo(START.plus(Event.DEFAULT_DURATION));
    }
}
