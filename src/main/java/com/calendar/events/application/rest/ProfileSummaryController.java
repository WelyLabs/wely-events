package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ProfileSummaryResponse;
import com.calendar.events.application.mappers.ProfileSummaryRestMapper;
import com.calendar.events.domain.services.FriendshipService;
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
    private final FriendshipService friendshipService;
    private final ProfileSummaryRestMapper mapper;

    public ProfileSummaryController(ProfileSummaryService profileSummaryService, PictureService pictureService,
                                    FriendshipService friendshipService, ProfileSummaryRestMapper mapper) {
        this.profileSummaryService = profileSummaryService;
        this.pictureService = pictureService;
        this.friendshipService = friendshipService;
        this.mapper = mapper;
    }

    /** Upcoming events and memories show their covers, signed in one round, and the caller's friends on them. */
    @GetMapping
    public Mono<ProfileSummaryResponse> summary(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return profileSummaryService.summaryOf(userId)
                .flatMap(summary -> Mono.zip(pictureService
                                .signCovers(Stream.concat(summary.upcoming().stream(), summary.memories().stream()).toList()),
                                friendshipService.viewerFriends(userId))
                        .map(known -> mapper.toResponse(summary, known.getT1(), known.getT2())));
    }
}
