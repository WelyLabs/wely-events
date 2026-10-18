package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.CalendarEntryResponse;
import com.calendar.events.application.dtos.CircleEntryResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.services.FriendCalendarService;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Instant;

@RestController
@RequestMapping("events")
public class FriendCalendarController {

    private final FriendCalendarService friendCalendarService;
    private final EventRestMapper mapper;

    public FriendCalendarController(FriendCalendarService friendCalendarService, EventRestMapper mapper) {
        this.friendCalendarService = friendCalendarService;
        this.mapper = mapper;
    }

    /**
     * The busy and visible entries of all the caller's intimate friends between {@code from} and {@code to},
     * 62 days at most (400 EVT-BUS-037 otherwise), each with whose it is.
     */
    @GetMapping("/me/circle")
    public Flux<CircleEntryResponse> getCircleCalendar(@RequestParam Instant from, @RequestParam Instant to,
                                                       @AuthenticationPrincipal Jwt jwt) {
        if (!to.isAfter(from)) {
            return Flux.error(new EventException(EventErrorCode.INVALID_CIRCLE_REQUEST));
        }
        String userId = jwt.getClaimAsString("businessId");
        return friendCalendarService.getCircleCalendar(userId, new TimeRange(from, to)).map(mapper::toResponse);
    }

    /** A friend's events between {@code from} and {@code to}, as much as the closeness allows. */
    @GetMapping("/users/{friendId}/calendar")
    public Flux<CalendarEntryResponse> getFriendCalendar(@PathVariable String friendId,
                                                         @RequestParam Instant from,
                                                         @RequestParam Instant to,
                                                         @AuthenticationPrincipal Jwt jwt) {
        if (!to.isAfter(from)) {
            return Flux.error(new EventException(EventErrorCode.INVALID_CALENDAR_REQUEST));
        }
        String userId = jwt.getClaimAsString("businessId");
        return friendCalendarService.getFriendCalendar(userId, friendId, new TimeRange(from, to))
                .map(mapper::toResponse);
    }
}
