package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendshipNoticeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Instant SINCE = Instant.parse("2025-10-18T09:00:00Z");
    private static final Instant LAST = Instant.parse("2026-05-02T18:00:00Z");

    @Test
    void reunionNudge_shouldCarryTheCandidate_keyedByRecipientAndDay() {
        ReunionCandidate hugo = new ReunionCandidate("hugo", "Hugo", Closeness.CLOSE, SINCE, LAST, "e1", "Ciné", 5);

        FriendshipNotice notice = FriendshipNotice.reunionNudge("me", hugo, DAY);

        assertThat(notice).isEqualTo(new FriendshipNotice(FriendshipNoticeType.REUNION_NUDGE, "me", "hugo", "Hugo",
                Closeness.CLOSE, LAST, "e1", 5, null, null, DAY));
        // Not the friend: one nudge a day per recipient, whoever it is about.
        assertThat(notice.key()).isEqualTo("REUNION_NUDGE:me:2026-10-18");
    }

    @Test
    void anniversary_shouldCarryTheYears_keyedByRecipientFriendAndYear() {
        FriendshipNotice notice = FriendshipNotice.anniversary("me", "lea", "Léa", Closeness.INTIMATE, 1, SINCE, DAY);

        assertThat(notice).isEqualTo(new FriendshipNotice(FriendshipNoticeType.FRIENDSHIP_ANNIVERSARY, "me", "lea", "Léa",
                Closeness.INTIMATE, null, null, null, 1, SINCE, DAY));
        assertThat(notice.key()).isEqualTo("FRIENDSHIP_ANNIVERSARY:me:lea:1");
    }

    @Test
    void constructor_shouldRequireTheTypeThePeopleAndTheDay() {
        assertThatThrownBy(() -> new FriendshipNotice(null, "me", "lea", null, null, null, null, null, null, null, DAY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FriendshipNotice(FriendshipNoticeType.REUNION_NUDGE, null, "lea", null, null, null,
                null, null, null, null, DAY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FriendshipNotice(FriendshipNoticeType.REUNION_NUDGE, "me", null, null, null, null,
                null, null, null, null, DAY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FriendshipNotice(FriendshipNoticeType.REUNION_NUDGE, "me", "lea", null, null, null,
                null, null, null, null, null)).isInstanceOf(NullPointerException.class);
    }
}
