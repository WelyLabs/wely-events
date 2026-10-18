package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.infrastructure.persistence.entities.FriendshipNoticeEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipNoticePersistenceMapperTest {

    private static final Instant RECORDED = Instant.parse("2026-10-18T06:00:00Z");
    private final FriendshipNoticePersistenceMapper mapper = Mappers.getMapper(FriendshipNoticePersistenceMapper.class);

    private static FriendshipNotice nudge() {
        return FriendshipNotice.reunionNudge("me", new ReunionCandidate("hugo", "Hugo", Closeness.CLOSE,
                Instant.parse("2025-01-01T10:00:00Z"), Instant.parse("2026-05-02T18:00:00Z"), "e1", "Ciné", 5),
                LocalDate.of(2026, 10, 18));
    }

    @Test
    void toEntity_shouldKeyTheNotice_unsent() {
        FriendshipNoticeEntity entity = mapper.toEntity(nudge(), RECORDED);

        assertThat(entity.getId()).isEqualTo("REUNION_NUDGE:me:2026-10-18");
        assertThat(entity.getType()).isEqualTo("REUNION_NUDGE");
        assertThat(entity.getCloseness()).isEqualTo("CLOSE");
        assertThat(entity.getMonthsApart()).isEqualTo(5);
        assertThat(entity.getRecordedAt()).isEqualTo(RECORDED);
        assertThat(entity.isPublished()).isFalse();
    }

    @Test
    void toDomain_shouldReadTheNoticeBack() {
        FriendshipNotice anniversary = FriendshipNotice.anniversary("me", "lea", "Léa", Closeness.INTIMATE, 2,
                Instant.parse("2024-10-18T09:00:00Z"), LocalDate.of(2026, 10, 18));

        assertThat(mapper.toDomain(mapper.toEntity(nudge(), RECORDED))).isEqualTo(nudge());
        assertThat(mapper.toDomain(mapper.toEntity(anniversary, RECORDED))).isEqualTo(anniversary);
    }
}
