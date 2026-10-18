package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.CoordinatesDto;
import com.calendar.events.application.dtos.ProfileSummaryResponse;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.ProfileSummary;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.models.Visibility;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileSummaryRestMapperTest {

    private final ProfileSummaryRestMapper mapper = Mappers.getMapper(ProfileSummaryRestMapper.class);

    @Test
    void toResponse_shouldCarryEverything_andShowEventsAsTheFeedDoes() {
        // Spring wires the event mapper in the application; here it is set by hand.
        ReflectionTestUtils.setField(mapper, "eventRestMapper", Mappers.getMapper(EventRestMapper.class));
        Event event = Event.builder().id("e1").title("Soirée jeux").startDate(Instant.parse("2026-10-14T18:00:00Z")).build();
        ProfileSummary summary = new ProfileSummary(23, 4, 6, 9, List.of(event), List.of(), 23,
                List.of(new ProfileSummary.Companion("lea", 12)),
                List.of(new ProfileSummary.Place("Chez Léa", new GeoPoint(45.77, 4.83), 3)),
                List.of(new ProfileSummary.EventBalance("e1", "Soirée jeux", -800)));

        ProfileSummaryResponse response = mapper.toResponse(summary, SignedUrls.NONE, ViewerFriends.NONE);

        assertThat(response.outingsThisYear()).isEqualTo(23);
        assertThat(response.upcoming().getFirst().getTitle()).isEqualTo("Soirée jeux");
        // The same rule as the feed: an event stored without visibility reads as FRIENDS.
        assertThat(response.upcoming().getFirst().getVisibility()).isEqualTo(Visibility.FRIENDS);
        assertThat(response.circle()).containsExactly(new ProfileSummaryResponse.CompanionResponse("lea", 12));
        assertThat(response.places()).containsExactly(
                new ProfileSummaryResponse.PlaceResponse("Chez Léa", new CoordinatesDto(45.77, 4.83), 3));
        assertThat(response.balances()).containsExactly(
                new ProfileSummaryResponse.EventBalanceResponse("e1", "Soirée jeux", -800));
    }

    @Test
    void toResponse_shouldSignTheCoversOfUpcomingEventsAndMemories() {
        ReflectionTestUtils.setField(mapper, "eventRestMapper", Mappers.getMapper(EventRestMapper.class));
        Event next = Event.builder().id("e1").image("events/e1/cover-a.jpg").build();
        Event memory = Event.builder().id("e2").image("/demo/events/picnic.jpg").build();
        ProfileSummary summary = new ProfileSummary(0, 0, 0, 0, List.of(next), List.of(memory), 1, List.of(), List.of(), List.of());

        ProfileSummaryResponse response = mapper.toResponse(summary,
                new SignedUrls(Map.of("events/e1/cover-a.jpg", "https://media/a")), ViewerFriends.NONE);

        assertThat(response.upcoming().getFirst().getImage()).isEqualTo("https://media/a");
        assertThat(response.memories().getFirst().getImage()).isEqualTo("/demo/events/picnic.jpg");
    }
}
