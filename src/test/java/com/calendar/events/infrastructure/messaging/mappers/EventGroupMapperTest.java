package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventGroupMapperTest {

    private final EventGroupMapper mapper = Mappers.getMapper(EventGroupMapper.class);

    private static EventEntity event(String organizer, List<String> participants, Long version) {
        return EventEntity.builder()
                .id("e1")
                .title("Board games")
                .organizerId(organizer)
                .participantIds(new ArrayList<>(participants))
                .groupVersion(version)
                .build();
    }

    @Test
    void toDto_shouldCarryTheOrganizerFirstThenParticipants() {
        EventGroupChangedDTO dto = mapper.toDto(event("org", List.of("a", "b"), 3L));

        assertThat(dto).isEqualTo(new EventGroupChangedDTO("e1", "Board games", List.of("org", "a", "b"), 3));
    }

    @Test
    void toDto_shouldListAnOrganizerWhoAlsoTakesPartOnce() {
        EventGroupChangedDTO dto = mapper.toDto(event("org", List.of("a", "org"), 1L));

        assertThat(dto.memberIds()).containsExactly("org", "a");
    }

    @Test
    void toDto_shouldSendVersionZero_whenTheEventPredatesTheGroups() {
        assertThat(mapper.toDto(event("org", List.of(), null)).version()).isZero();
    }

    @Test
    void members_shouldTolerateMissingFields() {
        EventEntity bare = new EventEntity();
        bare.setParticipantIds(null);

        assertThat(mapper.members(bare)).isEmpty();
    }
}
