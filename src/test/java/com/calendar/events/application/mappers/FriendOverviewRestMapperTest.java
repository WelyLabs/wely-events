package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.FriendOverviewResponse;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.FriendOverview;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.models.Visibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FriendOverviewRestMapperTest {

    private final FriendOverviewRestMapper mapper = Mappers.getMapper(FriendOverviewRestMapper.class);

    @BeforeEach
    void setUp() {
        // Spring wires the event mapper in the application; here it is set by hand.
        ReflectionTestUtils.setField(mapper, "eventRestMapper", Mappers.getMapper(EventRestMapper.class));
    }

    @Test
    void toResponse_shouldCarryEverything_andShowEventsAsTheFeedDoes() {
        Event next = Event.builder().id("e1").title("Board games").startDate(Instant.parse("2026-10-20T18:00:00Z")).build();
        Event memory = Event.builder().id("e2").title("Picnic").visibility(Visibility.PRIVATE).build();
        Event joinable = Event.builder().id("e3").title("Concert").visibility(Visibility.PUBLIC).build();

        FriendOverviewResponse response = mapper.toResponse(
                new FriendOverview(7, List.of(next), List.of(memory), List.of(joinable)), SignedUrls.NONE, ViewerFriends.NONE);

        assertThat(response.outingsTogether()).isEqualTo(7);
        assertThat(response.upcomingTogether().getFirst().getTitle()).isEqualTo("Board games");
        // An event stored without visibility reads as FRIENDS, as in the feed.
        assertThat(response.upcomingTogether().getFirst().getVisibility()).isEqualTo(Visibility.FRIENDS);
        assertThat(response.memoriesTogether().getFirst().getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(response.friendUpcoming().getFirst().getTitle()).isEqualTo("Concert");
    }

    @Test
    void toResponse_shouldShowTheSignedCovers_inEveryList() {
        Event next = Event.builder().id("e1").image("events/e1/cover-a.jpg").build();
        Event memory = Event.builder().id("e2").image("/demo/events/picnic.jpg").build();
        Event joinable = Event.builder().id("e3").image("events/e3/cover-c.jpg").build();

        FriendOverviewResponse response = mapper.toResponse(
                new FriendOverview(1, List.of(next), List.of(memory), List.of(joinable)),
                new SignedUrls(Map.of("events/e1/cover-a.jpg", "https://media/a", "events/e3/cover-c.jpg", "https://media/c")),
                ViewerFriends.NONE);

        assertThat(response.upcomingTogether().getFirst().getImage()).isEqualTo("https://media/a");
        assertThat(response.memoriesTogether().getFirst().getImage()).isEqualTo("/demo/events/picnic.jpg");
        assertThat(response.friendUpcoming().getFirst().getImage()).isEqualTo("https://media/c");
    }
}
