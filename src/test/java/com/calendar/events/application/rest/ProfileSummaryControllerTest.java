package com.calendar.events.application.rest;

import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.application.dtos.ProfileSummaryResponse;
import com.calendar.events.application.mappers.ProfileSummaryRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.ProfileSummary;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.ProfileSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileSummaryControllerTest {

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private ProfileSummaryService profileSummaryService;

    @Mock
    private ProfileSummaryRestMapper mapper;

    @Mock
    private MediaStore mediaStore;

    @Mock
    private Jwt jwt;

    private ProfileSummaryController controller;

    @BeforeEach
    void setUp() {
        lenient().when(friendshipService.viewerFriends(anyString())).thenReturn(Mono.just(ViewerFriends.NONE));
        controller = new ProfileSummaryController(profileSummaryService, new PictureService(mediaStore), friendshipService,
                mapper);
    }

    @Test
    void summary_shouldOnlyEverBeTheCallersOwn() {
        ProfileSummary summary = new ProfileSummary(0, 0, 0, 0, List.of(), List.of(), 0, List.of(), List.of(), List.of());
        ProfileSummaryResponse response = new ProfileSummaryResponse(0, 0, 0, 0, List.of(), List.of(), 0, List.of(), List.of(), List.of());
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(profileSummaryService.summaryOf("me")).thenReturn(Mono.just(summary));
        when(mapper.toResponse(summary, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.summary(jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void summary_shouldSignTheCoversOfUpcomingEventsAndMemories_inOneCall() {
        Event next = Event.builder().id("e1").image("events/e1/cover-a.jpg").build();
        Event memory = Event.builder().id("e2").image("events/e2/cover-b.jpg").build();
        ProfileSummary summary = new ProfileSummary(0, 0, 0, 0, List.of(next), List.of(memory), 1, List.of(), List.of(), List.of());
        SignedUrls urls = new SignedUrls(Map.of("events/e1/cover-a.jpg", "https://media/a", "events/e2/cover-b.jpg", "https://media/b"));
        ProfileSummaryResponse response = new ProfileSummaryResponse(0, 0, 0, 0, List.of(), List.of(), 1, List.of(), List.of(), List.of());
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(profileSummaryService.summaryOf("me")).thenReturn(Mono.just(summary));
        when(mediaStore.urls(List.of("events/e1/cover-a.jpg", "events/e2/cover-b.jpg"))).thenReturn(Mono.just(urls.byKey()));
        when(mapper.toResponse(summary, urls, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.summary(jwt)).expectNext(response).verifyComplete();
        verify(mediaStore, times(1)).urls(any());
    }
}
