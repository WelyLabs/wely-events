package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthdayNoticeTest {

    @Test
    void key_shouldNameTheHonoreeTheRecipientTheBirthdayYearAndTheType() {
        BirthdayNotice notice = new BirthdayNotice(BirthdayNoticeType.SURPRISE_PROPOSAL, "bob", "lea", "Léa",
                LocalDate.parse("2027-01-02"), 21, Closeness.INTIMATE, null, null, null);

        // The birthday's year, not the sending day's: a proposal sent in December is for next year's.
        assertThat(notice.key()).isEqualTo("lea:bob:2027:SURPRISE_PROPOSAL");
    }
}
