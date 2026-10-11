package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.FreeSlotSearchRequest;
import com.calendar.events.application.dtos.TimeRangeResponse;
import com.calendar.events.application.mappers.FreeSlotRestMapper;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@RestController
@RequestMapping("events")
public class FreeSlotController {

    private final FreeSlotService freeSlotService;
    private final FreeSlotRestMapper mapper;

    public FreeSlotController(FreeSlotService freeSlotService, FreeSlotRestMapper mapper) {
        this.freeSlotService = freeSlotService;
        this.mapper = mapper;
    }

    /** Free ranges, inside the requested period, where the requester and these friends are all free. */
    @PostMapping("/me/free-slots")
    public Flux<TimeRangeResponse> findCommonFreeSlots(@Valid @RequestBody FreeSlotSearchRequest request,
                                                       @AuthenticationPrincipal Jwt jwt) {
        if (!request.end().isAfter(request.start())) {
            return Flux.error(new EventException(EventErrorCode.INVALID_SLOT_SEARCH));
        }
        String userId = jwt.getClaimAsString("businessId");
        return freeSlotService.findCommonFreeRanges(userId, request.friendIds(),
                        new TimeRange(request.start(), request.end()),
                        Duration.ofMinutes(request.durationMinutes()))
                .map(mapper::toResponse);
    }
}
