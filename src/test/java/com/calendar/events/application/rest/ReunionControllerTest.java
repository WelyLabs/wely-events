package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ReunionResponse;
import com.calendar.events.application.mappers.FriendshipRestMapper;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.services.ReunionService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReunionControllerTest {

    @Mock
    private ReunionService reunionService;
    @Mock
    private Jwt jwt;

    private ReunionController controller;

    @BeforeEach
    void setUp() {
        controller = new ReunionController(reunionService, Mappers.getMapper(FriendshipRestMapper.class));
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
    }

    @Test
    void getReunions_shouldListTheCallersCandidates() {
        Instant last = Instant.parse("2026-05-02T18:00:00Z");
        when(reunionService.reunionsFor("me")).thenReturn(Flux.just(new ReunionCandidate("hugo", "Hugo", Closeness.CLOSE,
                Instant.parse("2025-01-01T10:00:00Z"), last, "e1", "Ciné", 5)));

        StepVerifier.create(controller.getReunions(jwt))
                .expectNext(new ReunionResponse("hugo", "Hugo", Closeness.CLOSE, last, "e1", "Ciné", 5))
                .verifyComplete();
    }

    @Test
    void dismiss_shouldSnoozeOnBehalfOfTheCaller() {
        when(reunionService.dismiss("me", "hugo")).thenReturn(Mono.empty());

        StepVerifier.create(controller.dismiss("hugo", jwt)).verifyComplete();
        verify(reunionService).dismiss("me", "hugo");
    }

    @Test
    void dismiss_shouldPassANotFoundOn() {
        when(reunionService.dismiss("me", "stranger"))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.FRIEND_NOT_FOUND)));

        StepVerifier.create(controller.dismiss("stranger", jwt))
                .expectErrorMatches(error -> error instanceof BusinessException e
                        && e.getErrorCode() == BusinessErrorCode.FRIEND_NOT_FOUND)
                .verify();
    }
}
