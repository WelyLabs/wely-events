package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.FriendshipMemoriesResponse;
import com.calendar.events.application.dtos.ReunionResponse;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.FriendshipMemories;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.models.SignedUrls;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipRestMapperTest {

    private static final Instant SINCE = Instant.parse("2025-03-03T10:00:00Z");
    private final FriendshipRestMapper mapper = Mappers.getMapper(FriendshipRestMapper.class);

    @Test
    void toResponse_shouldCarryTheCandidate_withoutItsFriendshipStart() {
        ReunionCandidate never = new ReunionCandidate("hugo", "Hugo", Closeness.INTIMATE, SINCE, null, null, null, null);

        assertThat(mapper.toResponse(never))
                .isEqualTo(new ReunionResponse("hugo", "Hugo", Closeness.INTIMATE, null, null, null, null));
    }

    @Test
    void toResponse_shouldCarryTheMemories_coversSigned() {
        Event stored = Event.builder().id("a").title("Picnic").startDate(SINCE).image("events/a/cover.jpg").build();
        Event none = Event.builder().id("b").title("Bar").startDate(SINCE.plusSeconds(60)).build();

        FriendshipMemoriesResponse response = mapper.toResponse(new FriendshipMemories("lea", "Léa", Closeness.CLOSE, SINCE,
                1, 400, 2, stored, none, List.of(stored, none)), new SignedUrls(Map.of("events/a/cover.jpg", "https://media/a")));

        assertThat(response).isEqualTo(new FriendshipMemoriesResponse("lea", "Léa", Closeness.CLOSE, SINCE, 1, 400, 2,
                new FriendshipMemoriesResponse.MemoryResponse("a", "Picnic", SINCE, "https://media/a"),
                new FriendshipMemoriesResponse.MemoryResponse("b", "Bar", SINCE.plusSeconds(60), null),
                List.of(new FriendshipMemoriesResponse.MemoryResponse("a", "Picnic", SINCE, "https://media/a"),
                        new FriendshipMemoriesResponse.MemoryResponse("b", "Bar", SINCE.plusSeconds(60), null))));
    }

    @Test
    void toMemory_shouldBeNull_forNoEvent() {
        assertThat(mapper.toMemory(null, SignedUrls.NONE)).isNull();
    }
}
