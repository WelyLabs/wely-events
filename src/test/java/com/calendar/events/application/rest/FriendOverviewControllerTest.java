package com.calendar.events.application.rest;

import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.application.dtos.FriendOverviewResponse;
import com.calendar.events.application.mappers.FriendOverviewRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.FriendOverview;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.FriendOverviewService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendOverviewControllerTest {

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private FriendOverviewService service;
    @Mock
    private FriendOverviewRestMapper mapper;
    @Mock
    private MediaStore mediaStore;
    @Mock
    private Jwt jwt;

    private FriendOverviewController controller;

    @BeforeEach
    void setUp() {
        lenient().when(friendshipService.viewerFriends(anyString())).thenReturn(Mono.just(ViewerFriends.NONE));
        controller = new FriendOverviewController(service, new PictureService(mediaStore), friendshipService, mapper);
    }

    @Test
    void getFriendOverview_shouldAskOnBehalfOfTheCaller() {
        FriendOverview overview = new FriendOverview(3, List.of(), List.of(), List.of());
        FriendOverviewResponse response = new FriendOverviewResponse(3, List.of(), List.of(), List.of());
        when(jwt.getClaimAsString("businessId")).thenReturn("alice");
        when(service.overviewOf("alice", "bob")).thenReturn(Mono.just(overview));
        when(mapper.toResponse(overview, SignedUrls.NONE, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.getFriendOverview("bob", jwt)).expectNext(response).verifyComplete();
        verifyNoInteractions(mediaStore);
    }

    @Test
    void getFriendOverview_shouldSignTheCoversOfAllThreeLists_inOneCall() {
        Event next = Event.builder().id("e1").image("events/e1/cover-a.jpg").build();
        Event memory = Event.builder().id("e2").image("events/e2/cover-b.jpg").build();
        Event joinable = Event.builder().id("e3").image("events/e3/cover-c.jpg").build();
        FriendOverview overview = new FriendOverview(1, List.of(next), List.of(memory), List.of(joinable));
        SignedUrls urls = new SignedUrls(Map.of("events/e1/cover-a.jpg", "https://media/a",
                "events/e2/cover-b.jpg", "https://media/b", "events/e3/cover-c.jpg", "https://media/c"));
        FriendOverviewResponse response = new FriendOverviewResponse(1, List.of(), List.of(), List.of());
        when(jwt.getClaimAsString("businessId")).thenReturn("alice");
        when(service.overviewOf("alice", "bob")).thenReturn(Mono.just(overview));
        when(mediaStore.urls(List.of("events/e1/cover-a.jpg", "events/e2/cover-b.jpg", "events/e3/cover-c.jpg")))
                .thenReturn(Mono.just(urls.byKey()));
        when(mapper.toResponse(overview, urls, ViewerFriends.NONE)).thenReturn(response);

        StepVerifier.create(controller.getFriendOverview("bob", jwt)).expectNext(response).verifyComplete();
        verify(mediaStore, times(1)).urls(any());
    }

    @Test
    void getFriendOverview_shouldPassOnTheRefusal_whenNotFriends() {
        when(jwt.getClaimAsString("businessId")).thenReturn("alice");
        when(service.overviewOf("alice", "carol"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.NOT_A_FRIEND)));

        StepVerifier.create(controller.getFriendOverview("carol", jwt))
                .expectErrorMatches(e -> e instanceof BusinessException ex && ex.getErrorCode() == BusinessErrorCode.NOT_A_FRIEND)
                .verify();
        verifyNoInteractions(mediaStore, mapper);
    }
}
