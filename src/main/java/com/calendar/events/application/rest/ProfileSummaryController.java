package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ProfileSummaryResponse;
import com.calendar.events.application.mappers.ProfileSummaryRestMapper;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.ProfileSummaryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.stream.Stream;

/** The caller's own profile summary: only ever theirs, the id comes from the token. */
@RestController
@RequestMapping("events/me/summary")
public class ProfileSummaryController {

    private final ProfileSummaryService profileSummaryService;
    private final PictureService pictureService;
    private final ProfileSummaryRestMapper mapper;

    public ProfileSummaryController(ProfileSummaryService profileSummaryService, PictureService pictureService,
                                    ProfileSummaryRestMapper mapper) {
        this.profileSummaryService = profileSummaryService;
        this.pictureService = pictureService;
        this.mapper = mapper;
    }

    /** Upcoming events and memories show their covers, signed in one round. */
    @GetMapping
    public Mono<ProfileSummaryResponse> summary(@AuthenticationPrincipal Jwt jwt) {
        return profileSummaryService.summaryOf(jwt.getClaimAsString("businessId"))
                .flatMap(summary -> pictureService
                        .signCovers(Stream.concat(summary.upcoming().stream(), summary.memories().stream()).toList())
                        .map(urls -> mapper.toResponse(summary, urls)));
    }
}
