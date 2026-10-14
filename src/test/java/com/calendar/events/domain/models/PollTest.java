package com.calendar.events.domain.models;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PollTest {

    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private static PollSlot candidate(String start, String end) {
        return new PollSlot(null, Instant.parse(start), Instant.parse(end));
    }

    private static final PollSlot THURSDAY = candidate("2026-10-16T17:00:00Z", "2026-10-16T19:00:00Z");
    private static final PollSlot FRIDAY = candidate("2026-10-17T17:00:00Z", "2026-10-17T19:00:00Z");
    private static final PollSlot SATURDAY = candidate("2026-10-18T10:00:00Z", "2026-10-18T12:00:00Z");

    private static void assertInvalid(List<PollSlot> candidates) {
        assertThatThrownBy(() -> Poll.open(candidates, NOW))
                .isInstanceOf(EventException.class)
                .extracting(error -> ((EventException) error).getErrorCode())
                .isEqualTo(EventErrorCode.POLL_INVALID);
    }

    @Test
    void open_shouldSortTheSlotsByStartAndGiveEachAnId() {
        Poll poll = Poll.open(List.of(SATURDAY, THURSDAY, FRIDAY), NOW);

        assertThat(poll.status()).isEqualTo(PollStatus.OPEN);
        assertThat(poll.fixedSlotId()).isNull();
        assertThat(poll.ballots()).isEmpty();
        assertThat(poll.slots()).extracting(PollSlot::start)
                .containsExactly(THURSDAY.start(), FRIDAY.start(), SATURDAY.start());
        assertThat(poll.slots()).extracting(PollSlot::id).doesNotContainNull().doesNotHaveDuplicates()
                .allMatch(id -> id.length() == 8);
        assertThat(poll.earliest().start()).isEqualTo(THURSDAY.start());
    }

    @Test
    void open_shouldAcceptFiveSlots() {
        List<PollSlot> five = new ArrayList<>();
        for (int day = 0; day < 5; day++) {
            five.add(new PollSlot(null, NOW.plusSeconds(86_400L * (day + 1)), NOW.plusSeconds(86_400L * (day + 1) + 3600)));
        }

        assertThat(Poll.open(five, NOW).slots()).hasSize(5);
    }

    @Test
    void open_shouldRejectFewerThanTwoSlots() {
        assertInvalid(List.of(THURSDAY));
        assertInvalid(List.of());
        assertInvalid(null);
    }

    @Test
    void open_shouldRejectMoreThanFiveSlots() {
        List<PollSlot> six = new ArrayList<>();
        for (int day = 0; day < 6; day++) {
            six.add(new PollSlot(null, NOW.plusSeconds(86_400L * (day + 1)), NOW.plusSeconds(86_400L * (day + 1) + 3600)));
        }

        assertInvalid(six);
    }

    @Test
    void open_shouldRejectASlotEndingBeforeItStarts() {
        assertInvalid(List.of(THURSDAY, candidate("2026-10-17T19:00:00Z", "2026-10-17T17:00:00Z")));
        assertInvalid(List.of(THURSDAY, candidate("2026-10-17T19:00:00Z", "2026-10-17T19:00:00Z")));
    }

    @Test
    void open_shouldRejectDuplicates() {
        assertInvalid(List.of(THURSDAY, candidate("2026-10-16T17:00:00Z", "2026-10-16T19:00:00Z")));
    }

    @Test
    void open_shouldRejectASlotInThePast() {
        assertInvalid(List.of(THURSDAY, candidate("2026-10-14T09:00:00Z", "2026-10-14T11:00:00Z")));
    }

    @Test
    void open_shouldRejectAMissingDate() {
        assertInvalid(List.of(THURSDAY, new PollSlot(null, null, FRIDAY.end())));
        assertInvalid(List.of(THURSDAY, new PollSlot(null, FRIDAY.start(), null)));
    }

    private static Poll votedOn() {
        Poll open = Poll.open(List.of(THURSDAY, FRIDAY), NOW);
        String first = open.slots().get(0).id();
        String second = open.slots().get(1).id();
        Map<String, Map<String, PollAnswer>> ballots = new LinkedHashMap<>();
        ballots.put("lea", Map.of(first, PollAnswer.YES, second, PollAnswer.NO));
        ballots.put("hugo", Map.of(first, PollAnswer.MAYBE, second, PollAnswer.YES));
        ballots.put("zoe", Map.of(first, PollAnswer.YES));
        return new Poll(PollStatus.OPEN, open.slots(), ballots, null);
    }

    @Test
    void tally_shouldListWhoAnsweredWhatPerSlot_inSlotOrder() {
        Poll poll = votedOn();

        List<PollSlotTally> tally = poll.tally();

        assertThat(tally).hasSize(2);
        assertThat(tally.get(0).slot()).isEqualTo(poll.slots().get(0));
        assertThat(tally.get(0).yes()).containsExactly("lea", "zoe");
        assertThat(tally.get(0).maybe()).containsExactly("hugo");
        assertThat(tally.get(0).no()).isEmpty();
        assertThat(tally.get(1).yes()).containsExactly("hugo");
        assertThat(tally.get(1).no()).containsExactly("lea");
    }

    @Test
    void yesVotersOf_shouldListOnlyTheYesVotersOfThatSlot() {
        Poll poll = votedOn();

        assertThat(poll.yesVotersOf(poll.slots().get(0).id())).containsExactly("lea", "zoe");
        assertThat(poll.yesVotersOf("unknown")).isEmpty();
    }

    @Test
    void hasSlot_shouldKnowOnlyItsOwnSlots() {
        Poll poll = votedOn();

        assertThat(poll.hasSlot(poll.slots().get(1).id())).isTrue();
        assertThat(poll.hasSlot("unknown")).isFalse();
        assertThat(poll.slot("unknown")).isEmpty();
    }

    @Test
    void acceptsBallot_shouldRefuseAnAnswerForAnUnknownSlot() {
        Poll poll = votedOn();
        String known = poll.slots().get(0).id();

        assertThat(poll.acceptsBallot(Map.of(known, PollAnswer.YES))).isTrue();
        assertThat(poll.acceptsBallot(Map.of())).isTrue();
        assertThat(poll.acceptsBallot(Map.of(known, PollAnswer.YES, "unknown", PollAnswer.NO))).isFalse();
    }

    @Test
    void isOpen_shouldFollowTheStatus() {
        assertThat(votedOn().isOpen()).isTrue();
        assertThat(new Poll(PollStatus.CLOSED, List.of(), null, "x").isOpen()).isFalse();
    }

    @Test
    void constructor_shouldTreatMissingCollectionsAsEmpty() {
        Poll poll = new Poll(PollStatus.OPEN, null, null, null);

        assertThat(poll.slots()).isEmpty();
        assertThat(poll.ballots()).isEmpty();
        assertThat(poll.tally()).isEmpty();
    }
}
