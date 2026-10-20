package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.InvitationRequest;
import com.calendar.events.application.dtos.ParticipantsResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.services.InvitationService;
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

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationControllerTest {

    @Mock
    private InvitationService invitationService;
    @Mock
    private EventRestMapper mapper;
    @Mock
    private Jwt jwt;

    private InvitationController controller;

    @BeforeEach
    void setUp() {
        controller = new InvitationController(invitationService, mapper);
        when(jwt.getClaimAsString("businessId")).thenReturn("robin");
    }

    @Test
    void invite_shouldInviteOnBehalfOfTheCaller_andAnswerWithTheInvitations() {
        Participants participants = new Participants(1, List.of("robin"), List.of(), List.of("lea"));
        ParticipantsResponse response = new ParticipantsResponse(1, List.of("robin"), List.of(), List.of("lea"));
        when(invitationService.invite("e1", "robin", "lea")).thenReturn(Mono.just(participants));
        when(mapper.toResponse(participants)).thenReturn(response);

        StepVerifier.create(controller.invite("e1", new InvitationRequest("lea"), jwt))
                .expectNext(response)
                .verifyComplete();
    }

    @Test
    void invite_shouldPassOnARefusal() {
        when(invitationService.invite("e1", "robin", "lea"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.FRIEND_NOT_FOUND)));

        StepVerifier.create(controller.invite("e1", new InvitationRequest("lea"), jwt))
                .expectError(BusinessException.class)
                .verify();
    }

    @Test
    void withdraw_shouldWithdrawOnBehalfOfTheCaller() {
        when(invitationService.withdraw("e1", "robin", "lea")).thenReturn(Mono.empty());

        StepVerifier.create(controller.withdraw("e1", "lea", jwt)).verifyComplete();
    }
}
