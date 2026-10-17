package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.infrastructure.persistence.entities.CalendarLinkEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarLinkPersistenceMapperTest {

    private static final Instant EXPIRY = Instant.parse("2026-10-17T10:05:00Z");
    private final CalendarLinkPersistenceMapper mapper = Mappers.getMapper(CalendarLinkPersistenceMapper.class);

    @Test
    void toEntity_shouldKeyTheDocumentByTheToken() {
        CalendarLinkEntity entity = mapper.toEntity(new CalendarLink("tok", "e1", "https://w/event/e1", EXPIRY));

        assertThat(entity.getId()).isEqualTo("tok");
        assertThat(entity.getEventId()).isEqualTo("e1");
        assertThat(entity.getPageUrl()).isEqualTo("https://w/event/e1");
        assertThat(entity.getExpiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    void toDomain_shouldReadTheTokenFromTheId() {
        CalendarLinkEntity entity = CalendarLinkEntity.builder().id("tok").eventId("e1").expiresAt(EXPIRY).build();

        assertThat(mapper.toDomain(entity)).isEqualTo(new CalendarLink("tok", "e1", null, EXPIRY));
    }
}
