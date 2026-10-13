package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ProfileSummaryResponse;
import com.calendar.events.application.mappers.ProfileSummaryRestMapper;
import com.calendar.events.domain.services.ProfileSummaryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** The caller's own profile summary: only ever theirs, the id comes from the token. */
@RestController
@RequestMapping("events/me/summary")
public class ProfileSummaryController {

    private final ProfileSummaryService profileSummaryService;
    private final ProfileSummaryRestMapper mapper;

    public ProfileSummaryController(ProfileSummaryService profileSummaryService, ProfileSummaryRestMapper mapper) {
        this.profileSummaryService = profileSummaryService;
        this.mapper = mapper;
    }

    @GetMapping
    public Mono<ProfileSummaryResponse> summary(@AuthenticationPrincipal Jwt jwt) {
        return profileSummaryService.summaryOf(jwt.getClaimAsString("businessId")).map(mapper::toResponse);
    }
}
