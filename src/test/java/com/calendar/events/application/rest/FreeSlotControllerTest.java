package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.FreeSlotSearchRequest;
import com.calendar.events.application.dtos.TimeRangeResponse;
import com.calendar.events.application.mappers.FreeSlotRestMapperImpl;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FreeSlotControllerTest {

    @Mock
    private FreeSlotService freeSlotService;

    @Mock
    private Jwt jwt;

    private FreeSlotController controller;

    private static final Instant START = Instant.parse("2026-10-16T14:00:00Z");
    private static final Instant END = Instant.parse("2026-10-18T20:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new FreeSlotController(freeSlotService, new FreeSlotRestMapperImpl());
    }

    @Test
    void findCommonFreeSlots_shouldSearchForTheTokenUser() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        TimeRange free = new TimeRange(START, START.plus(Duration.ofHours(2)));
        when(freeSlotService.findCommonFreeRanges("me", Set.of("lea"), new TimeRange(START, END), Duration.ofMinutes(90)))
                .thenReturn(Flux.just(free));

        StepVerifier.create(controller.findCommonFreeSlots(new FreeSlotSearchRequest(Set.of("lea"), START, END, 90), jwt))
                .expectNext(new TimeRangeResponse(free.start(), free.end()))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findCommonFreeSlots_shouldRejectAPeriodThatEndsBeforeItStarts() {
        StepVerifier.create(controller.findCommonFreeSlots(new FreeSlotSearchRequest(Set.of("lea"), END, START, 60), jwt))
                .expectErrorMatches(e -> e instanceof BusinessException ex
                        && ex.getErrorCode() == BusinessErrorCode.INVALID_SLOT_SEARCH)
                .verify(Duration.ofSeconds(10));

        verify(freeSlotService, never()).findCommonFreeRanges(any(), any(), any(), any());
    }
}
