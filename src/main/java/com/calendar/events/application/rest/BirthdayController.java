package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.BirthdayOccurrenceResponse;
import com.calendar.events.application.mappers.BirthdayRestMapper;
import com.calendar.events.domain.services.BirthdayService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Instant;

@RestController
@RequestMapping("events")
public class BirthdayController {

    private final BirthdayService birthdayService;
    private final BirthdayRestMapper mapper;

    public BirthdayController(BirthdayService birthdayService, BirthdayRestMapper mapper) {
        this.birthdayService = birthdayService;
        this.mapper = mapper;
    }

    /**
     * The caller's birthday and their friends' in [from, to), by UTC date, soonest first; the only
     * place birthdays are served. 400 EVT-BUS-032 beyond 400 days or when {@code to} is not after {@code from}.
     */
    @GetMapping("/me/birthdays")
    public Flux<BirthdayOccurrenceResponse> getMyBirthdays(@RequestParam Instant from, @RequestParam Instant to,
                                                           @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString("businessId");
        return birthdayService.birthdaysFor(userId, from, to).map(mapper::toResponse);
    }
}
