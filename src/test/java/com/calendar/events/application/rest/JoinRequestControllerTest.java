package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.domain.services.EventService;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JoinRequestControllerTest {

    @Mock
    private EventService eventService;
    @Mock
    private SocialGraph socialGraph;
    @Mock
    private EventRestMapper mapper;
    @Mock
    private Jwt jwt;

    private JoinRequestController controller;

    private static Event surprise() {
        return Event.builder().id("e1").organizerId("robin")
                .participantIds(new ArrayList<>(List.of("robin", "lea")))
                .requestedIds(new ArrayList<>()).build();
    }

    @BeforeEach
    void setUp() {
        controller = new JoinRequestController(eventService, socialGraph, mapper);
        when(jwt.getClaimAsString("businessId")).thenReturn("robin");
    }

    @Test
    void accept_shouldTakeTheAskerInAndAnswerWithTheParticipants() {
        Event event = surprise();
        ParticipantsResponse response = new ParticipantsResponse(2, List.of("robin", "lea"), List.of(), List.of(), List.of());
        when(eventService.acceptJoinRequest("e1", "robin", "lea")).thenReturn(Mono.just(event));
        when(socialGraph.friendIdsOf("robin")).thenReturn(Mono.just(Set.of()));
        when(mapper.toResponse(any(Participants.class))).thenReturn(response);

        StepVerifier.create(controller.accept("e1", "lea", jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void accept_shouldAnswerWithAnEmptyCircle_whenTheFriendsCannotBeRead() {
        ParticipantsResponse response = new ParticipantsResponse(2, List.of("robin", "lea"), List.of(), List.of(), List.of());
        when(eventService.acceptJoinRequest("e1", "robin", "lea")).thenReturn(Mono.just(surprise()));
        when(socialGraph.friendIdsOf("robin")).thenReturn(Mono.empty());
        when(mapper.toResponse(any(Participants.class))).thenReturn(response);

        StepVerifier.create(controller.accept("e1", "lea", jwt))
                .expectNext(response)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void accept_shouldPassOnARefusal() {
        when(eventService.acceptJoinRequest("e1", "robin", "lea"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.JOIN_REQUEST_NOT_FOUND)));

        StepVerifier.create(controller.accept("e1", "lea", jwt))
                .expectError(BusinessException.class)
                .verify(Duration.ofSeconds(10));
        verify(socialGraph, never()).friendIdsOf(any());
    }

    @Test
    void decline_shouldAnswerNothingAtAll() {
        when(eventService.declineJoinRequest("e1", "robin", "lea")).thenReturn(Mono.just(surprise()));

        StepVerifier.create(controller.decline("e1", "lea", jwt))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void decline_shouldPassOnARefusal() {
        when(eventService.declineJoinRequest("e1", "robin", "lea"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.EVENT_ACCESS_DENIED)));

        StepVerifier.create(controller.decline("e1", "lea", jwt))
                .expectError(BusinessException.class)
                .verify(Duration.ofSeconds(10));
    }
}
