package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.BirthdayOccurrenceResponse;
import com.calendar.events.domain.models.BirthdayOccurrence;
import com.calendar.events.domain.models.Closeness;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BirthdayRestMapperTest {

    private final BirthdayRestMapper mapper = Mappers.getMapper(BirthdayRestMapper.class);

    @Test
    void toResponse_shouldMapAnOccurrenceAndItsSurprise() {
        BirthdayOccurrence occurrence = new BirthdayOccurrence("lea", "Léa", LocalDate.parse("2027-03-12"), false,
                Closeness.CLOSE, false, new BirthdayOccurrence.Surprise("s1", "bob", true));

        assertThat(mapper.toResponse(occurrence)).isEqualTo(new BirthdayOccurrenceResponse("lea", "Léa",
                LocalDate.parse("2027-03-12"), false, Closeness.CLOSE, false,
                new BirthdayOccurrenceResponse.SurpriseResponse("s1", "bob", true)));
    }

    @Test
    void toResponse_shouldLeaveNoSurprise_whenThereIsNone() {
        BirthdayOccurrence mine = new BirthdayOccurrence("me", "Moi", LocalDate.parse("2027-03-20"), true, null, false, null);

        assertThat(mapper.toResponse(mine).surprise()).isNull();
        assertThat(mapper.toResponse((BirthdayOccurrence.Surprise) null)).isNull();
    }
}
