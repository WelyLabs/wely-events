package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ConflictCheckRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.ScheduleRequest;
import com.calendar.events.application.dtos.TimeRangeResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.application.mappers.FreeSlotRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.ScheduleService;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/** Schedule conflicts with the caller's agenda, and moving an event out of one. */
@RestController
@RequestMapping("events")
public class ScheduleController {

    private final ScheduleService scheduleService;
    private final PictureService pictureService;
    private final EventRestMapper eventMapper;
    private final FreeSlotRestMapper rangeMapper;

    public ScheduleController(ScheduleService scheduleService, PictureService pictureService,
                              EventRestMapper eventMapper, FreeSlotRestMapper rangeMapper) {
        this.scheduleService = scheduleService;
        this.pictureService = pictureService;
        this.eventMapper = eventMapper;
        this.rangeMapper = rangeMapper;
    }

    /** The caller's events that a time would clash with: what the form checks before an event is saved. */
    @PostMapping("/me/conflicts")
    public Flux<EventResponse> checkConflicts(@Valid @RequestBody ConflictCheckRequest request,
                                              @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return Mono.fromCallable(() -> range(request.start(), request.end()))
                .flatMapMany(range -> respond(scheduleService.conflictsWith(userId, range, request.excludedEventId())));
    }

    /** The caller's events that clash with this one, whether they take part in it or not. */
    @GetMapping("/{id}/conflicts")
    public Flux<EventResponse> getConflicts(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return respond(scheduleService.conflictsOf(id, jwt.getClaimAsString("businessId")));
    }

    /** Organizer only: times when everyone taking part is free, closest first. */
    @GetMapping("/{id}/schedule/proposals")
    public Flux<TimeRangeResponse> getProposals(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return scheduleService.proposalsFor(id, jwt.getClaimAsString("businessId")).map(rangeMapper::toResponse);
    }

    /** Organizer only: moves the event; the others taking part are told in its group. */
    @PutMapping("/{id}/schedule")
    public Mono<EventResponse> reschedule(@PathVariable String id, @Valid @RequestBody ScheduleRequest request,
                                          @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return Mono.fromCallable(() -> range(request.start(), request.end()))
                .flatMap(range -> scheduleService.reschedule(id, userId, range))
                .flatMap(event -> pictureService.signCovers(List.of(event))
                        .map(urls -> eventMapper.toResponse(event, urls)));
    }

    /** An end at or before the start is the caller's mistake, not a server error. */
    private static TimeRange range(Instant start, Instant end) {
        if (!end.isAfter(start)) {
            throw new EventException(EventErrorCode.SCHEDULE_INVALID);
        }
        return new TimeRange(start, end);
    }

    /** One signing round for the whole list, as for the feed. */
    private Flux<EventResponse> respond(Flux<Event> events) {
        return events.collectList()
                .flatMapMany(list -> pictureService.signCovers(list)
                        .flatMapIterable(urls -> list.stream().map(event -> eventMapper.toResponse(event, urls)).toList()));
    }
}
