package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.FriendOverviewResponse;
import com.calendar.events.application.mappers.FriendOverviewRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.FriendOverview;
import com.calendar.events.domain.services.FriendOverviewService;
import com.calendar.events.domain.services.PictureService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Stream;

@RestController
@RequestMapping("events")
public class FriendOverviewController {

    private final FriendOverviewService friendOverviewService;
    private final PictureService pictureService;
    private final FriendOverviewRestMapper mapper;

    public FriendOverviewController(FriendOverviewService friendOverviewService, PictureService pictureService,
                                    FriendOverviewRestMapper mapper) {
        this.friendOverviewService = friendOverviewService;
        this.pictureService = pictureService;
        this.mapper = mapper;
    }

    /** A friend's page, seen by the caller; the covers of all three lists are signed in one round. */
    @GetMapping("/users/{friendId}/overview")
    public Mono<FriendOverviewResponse> getFriendOverview(@PathVariable String friendId,
                                                          @AuthenticationPrincipal Jwt jwt) {
        return friendOverviewService.overviewOf(jwt.getClaimAsString("businessId"), friendId)
                .flatMap(overview -> pictureService.signCovers(eventsOf(overview))
                        .map(urls -> mapper.toResponse(overview, urls)));
    }

    private static List<Event> eventsOf(FriendOverview overview) {
        return Stream.of(overview.upcomingTogether(), overview.memoriesTogether(), overview.friendUpcoming())
                .flatMap(List::stream)
                .toList();
    }
}
