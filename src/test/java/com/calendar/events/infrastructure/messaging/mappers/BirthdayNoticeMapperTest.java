package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.infrastructure.messaging.models.BirthdayNoticeDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthdayNoticeMapperTest {

    private final BirthdayNoticeMapper mapper = Mappers.getMapper(BirthdayNoticeMapper.class);

    @Test
    void toDto_shouldWriteTheDateAsYyyyMmDd_andTheEnumsByName() {
        BirthdayNotice notice = new BirthdayNotice(BirthdayNoticeType.SURPRISE_CREATED, "zoe", "lea", "Léa",
                LocalDate.parse("2027-03-02"), 12, Closeness.INTIMATE, "s1", "bob", "bob_l");

        assertThat(mapper.toDto(notice)).isEqualTo(new BirthdayNoticeDTO("SURPRISE_CREATED", "zoe", "lea", "Léa",
                "2027-03-02", 12, "INTIMATE", "s1", "bob", "bob_l"));
    }

    @Test
    void toDto_shouldLeaveTheClosenessNull_forTheHonoree() {
        BirthdayNotice notice = new BirthdayNotice(BirthdayNoticeType.BIRTHDAY_TODAY, "lea", "lea", "Léa",
                LocalDate.parse("2027-03-12"), 0, null, null, null, null);

        assertThat(mapper.toDto(notice).closeness()).isNull();
    }
}
