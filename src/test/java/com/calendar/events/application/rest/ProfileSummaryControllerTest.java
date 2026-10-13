package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ProfileSummaryResponse;
import com.calendar.events.application.mappers.ProfileSummaryRestMapper;
import com.calendar.events.domain.models.ProfileSummary;
import com.calendar.events.domain.services.ProfileSummaryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileSummaryControllerTest {

    @Mock
    private ProfileSummaryService profileSummaryService;

    @Mock
    private ProfileSummaryRestMapper mapper;

    @Mock
    private Jwt jwt;

    @InjectMocks
    private ProfileSummaryController controller;

    @Test
    void summary_shouldOnlyEverBeTheCallersOwn() {
        ProfileSummary summary = new ProfileSummary(0, 0, 0, 0, List.of(), List.of(), 0, List.of(), List.of(), List.of());
        ProfileSummaryResponse response = new ProfileSummaryResponse(0, 0, 0, 0, List.of(), List.of(), 0, List.of(), List.of(), List.of());
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(profileSummaryService.summaryOf("me")).thenReturn(Mono.just(summary));
        when(mapper.toResponse(summary)).thenReturn(response);

        StepVerifier.create(controller.summary(jwt)).expectNext(response).verifyComplete();
    }
}
