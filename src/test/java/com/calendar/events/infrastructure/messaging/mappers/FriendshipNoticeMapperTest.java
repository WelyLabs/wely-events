package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.infrastructure.messaging.models.FriendshipNoticeDTO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipNoticeMapperTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private final FriendshipNoticeMapper mapper = Mappers.getMapper(FriendshipNoticeMapper.class);

    @Test
    void toDto_shouldWriteANudge_withItsLastOutingAsIso8601() {
        FriendshipNotice nudge = FriendshipNotice.reunionNudge("me", new ReunionCandidate("hugo", "Hugo", Closeness.CLOSE,
                Instant.parse("2025-01-01T10:00:00Z"), Instant.parse("2026-05-02T18:00:00Z"), "e1", "Ciné", 5), DAY);

        assertThat(mapper.toDto(nudge)).isEqualTo(new FriendshipNoticeDTO("REUNION_NUDGE", "me", "hugo", "Hugo", "CLOSE",
                "2026-05-02T18:00:00Z", "e1", 5, null, null));
    }

    @Test
    void toDto_shouldLeaveTheLastOutingNull_whenThereWasNone() {
        FriendshipNotice nudge = FriendshipNotice.reunionNudge("me", new ReunionCandidate("hugo", "Hugo",
                Closeness.INTIMATE, null, null, null, null, null), DAY);

        FriendshipNoticeDTO dto = mapper.toDto(nudge);

        assertThat(dto.lastSharedAt()).isNull();
        assertThat(dto.lastSharedEventId()).isNull();
        assertThat(dto.monthsApart()).isNull();
    }

    @Test
    void toDto_shouldWriteAnAnniversary_withItsYearsAndStart() {
        FriendshipNotice anniversary = FriendshipNotice.anniversary("me", "lea", "Léa", Closeness.INTIMATE, 2,
                Instant.parse("2024-10-18T09:00:00Z"), DAY);

        assertThat(mapper.toDto(anniversary)).isEqualTo(new FriendshipNoticeDTO("FRIENDSHIP_ANNIVERSARY", "me", "lea",
                "Léa", "INTIMATE", null, null, null, 2, "2024-10-18T09:00:00Z"));
    }
}
