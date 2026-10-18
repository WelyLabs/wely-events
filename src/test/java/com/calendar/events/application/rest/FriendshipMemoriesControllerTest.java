package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.FriendshipMemoriesResponse;
import com.calendar.events.application.mappers.FriendshipRestMapper;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.FriendshipMemories;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.FriendshipMemoriesService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipMemoriesControllerTest {

    private static final Instant SINCE = Instant.parse("2025-03-03T10:00:00Z");

    @Mock
    private FriendshipMemoriesService memoriesService;
    @Mock
    private MediaStore mediaStore;
    @Mock
    private Jwt jwt;

    private FriendshipMemoriesController controller;

    @BeforeEach
    void setUp() {
        controller = new FriendshipMemoriesController(memoriesService, new PictureService(mediaStore),
                Mappers.getMapper(FriendshipRestMapper.class));
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
    }

    private static Event outing(String id, String image) {
        return Event.builder().id(id).title("Outing " + id).startDate(SINCE.plusSeconds(86_400)).image(image).build();
    }

    @Test
    void getMemories_shouldSignEveryCoverInOneCall() {
        Event first = outing("a", "events/a/cover.jpg");
        Event last = outing("c", "/demo/events/picnic.jpg");
        Event highlight = outing("b", "events/b/cover.jpg");
        when(memoriesService.memoriesOf("me", "lea")).thenReturn(Mono.just(new FriendshipMemories("lea", "Léa",
                Closeness.INTIMATE, SINCE, 1, 594, 3, first, last, List.of(highlight, first))));
        when(mediaStore.urls(any())).thenReturn(Mono.just(Map.of("events/a/cover.jpg", "https://media/a",
                "events/b/cover.jpg", "https://media/b")));

        StepVerifier.create(controller.getMemories("lea", jwt))
                .assertNext(response -> {
                    assertThat(response.name()).isEqualTo("Léa");
                    assertThat(response.years()).isEqualTo(1);
                    assertThat(response.days()).isEqualTo(594);
                    assertThat(response.sharedCount()).isEqualTo(3);
                    assertThat(response.first()).isEqualTo(new FriendshipMemoriesResponse.MemoryResponse("a", "Outing a",
                            first.getStartDate(), "https://media/a"));
                    assertThat(response.last().imageUrl()).isEqualTo("/demo/events/picnic.jpg");
                    assertThat(response.highlights()).extracting(FriendshipMemoriesResponse.MemoryResponse::imageUrl)
                            .containsExactly("https://media/b", "https://media/a");
                })
                .verifyComplete();
        verify(mediaStore, times(1)).urls(any());
    }

    @Test
    void getMemories_shouldAnswerWithoutFirstNorLast_andSignNothing_withoutAnOuting() {
        when(memoriesService.memoriesOf("me", "lea")).thenReturn(Mono.just(new FriendshipMemories("lea", "Léa",
                Closeness.FRIEND, SINCE, 0, 20, 0, null, null, List.of())));

        StepVerifier.create(controller.getMemories("lea", jwt))
                .assertNext(response -> {
                    assertThat(response.first()).isNull();
                    assertThat(response.last()).isNull();
                    assertThat(response.highlights()).isEmpty();
                })
                .verifyComplete();
        verify(mediaStore, never()).urls(any());
    }

    @Test
    void getMemories_shouldPassANotFoundOn() {
        when(memoriesService.memoriesOf("me", "stranger"))
                .thenReturn(Mono.error(new EventException(EventErrorCode.FRIEND_NOT_FOUND)));

        StepVerifier.create(controller.getMemories("stranger", jwt))
                .expectErrorMatches(error -> error instanceof EventException e
                        && e.getErrorCode() == EventErrorCode.FRIEND_NOT_FOUND)
                .verify();
    }
}
