package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.infrastructure.persistence.entities.BirthdayNoticeEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthdayNoticePersistenceMapperTest {

    private final BirthdayNoticePersistenceMapper mapper = Mappers.getMapper(BirthdayNoticePersistenceMapper.class);
    private static final Instant NOW = Instant.parse("2027-02-19T07:00:00Z");

    @Test
    void toEntity_shouldKeyTheNotice_andRecordItUnsent() {
        BirthdayNotice notice = new BirthdayNotice(BirthdayNoticeType.SURPRISE_PROPOSAL, "bob", "lea", "Léa",
                LocalDate.parse("2027-03-12"), 21, Closeness.INTIMATE, "s1", "max", "max_r");

        BirthdayNoticeEntity entity = mapper.toEntity(notice, NOW);

        assertThat(entity.getId()).isEqualTo("lea:bob:2027:SURPRISE_PROPOSAL");
        assertThat(entity.getType()).isEqualTo("SURPRISE_PROPOSAL");
        assertThat(entity.getCloseness()).isEqualTo("INTIMATE");
        assertThat(entity.getRecordedAt()).isEqualTo(NOW);
        assertThat(entity.isPublished()).isFalse();
        assertThat(mapper.toDomain(entity)).isEqualTo(notice);
    }

    @Test
    void toDomain_shouldKeepANullCloseness_forTheHonoree() {
        BirthdayNotice notice = new BirthdayNotice(BirthdayNoticeType.BIRTHDAY_TODAY, "lea", "lea", "Léa",
                LocalDate.parse("2027-03-12"), 0, null, null, null, null);

        assertThat(mapper.toDomain(mapper.toEntity(notice, NOW))).isEqualTo(notice);
    }
}
