package com.calendar.events.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The code convention, checked on every value so a new code cannot break it unnoticed. */
class TechnicalErrorCodeTest {

    private static List<String> codes() {
        return Arrays.stream(TechnicalErrorCode.values()).map(TechnicalErrorCode::getCode).toList();
    }

    @Test
    void values_shouldFollowTheTechnicalCodeFormat() {
        assertThat(codes()).allMatch(code -> code.matches("^EVT-TEC-\\d{3}$"));
    }

    @Test
    void values_shouldHaveUniqueCodes() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    void values_shouldAnswerAServerError() {
        // 5xx, not only 502: ERASURE_INCOMPLETE answers 503.
        assertThat(TechnicalErrorCode.values())
                .allMatch(error -> error.getHttpStatus().is5xxServerError(), "answers 5xx");
    }

    @Test
    void values_shouldNotReuseACodeTheHandlerWritesItself() {
        assertThat(codes()).doesNotContainAnyElementsOf(GlobalErrorHandler.HANDLER_CODES);
    }

    @Test
    void values_shouldNotOverlapBusinessCodes() {
        Set<String> business = Arrays.stream(BusinessErrorCode.values())
                .map(BusinessErrorCode::getCode)
                .collect(Collectors.toSet());

        assertThat(codes()).doesNotContainAnyElementsOf(business);
    }
}
