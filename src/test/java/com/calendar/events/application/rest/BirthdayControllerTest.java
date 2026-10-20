package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.BirthdayOccurrenceResponse;
import com.calendar.events.application.mappers.BirthdayRestMapper;
import com.calendar.events.domain.models.BirthdayOccurrence;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.services.BirthdayService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BirthdayControllerTest {

    private static final Instant FROM = Instant.parse("2027-03-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-04-01T00:00:00Z");

    @Mock
    private BirthdayService service;
    @Mock
    private Jwt jwt;

    private BirthdayController controller;

    @BeforeEach
    void setUp() {
        controller = new BirthdayController(service, Mappers.getMapper(BirthdayRestMapper.class));
    }

    @Test
    void getMyBirthdays_shouldListThemForTheCaller() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(service.birthdaysFor("me", FROM, TO)).thenReturn(Flux.just(new BirthdayOccurrence("lea", "Léa",
                LocalDate.parse("2027-03-12"), false, Closeness.CLOSE, true, null)));

        StepVerifier.create(controller.getMyBirthdays(FROM, TO, jwt))
                .expectNext(new BirthdayOccurrenceResponse("lea", "Léa", LocalDate.parse("2027-03-12"), false,
                        Closeness.CLOSE, true, null))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMyBirthdays_shouldPassARefusedRangeOn() {
        when(jwt.getClaimAsString("businessId")).thenReturn("me");
        when(service.birthdaysFor("me", TO, FROM)).thenReturn(Flux.error(new BusinessException(BusinessErrorCode.INVALID_BIRTHDAY_RANGE)));

        StepVerifier.create(controller.getMyBirthdays(TO, FROM, jwt))
                .expectErrorMatches(e -> e instanceof BusinessException ex
                        && ex.getErrorCode() == BusinessErrorCode.INVALID_BIRTHDAY_RANGE)
                .verify(Duration.ofSeconds(10));
    }
}
