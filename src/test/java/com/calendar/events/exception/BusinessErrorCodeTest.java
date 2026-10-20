package com.calendar.events.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The code convention, checked on every value so a new code cannot break it unnoticed. */
class BusinessErrorCodeTest {

    private static List<String> codes() {
        return Arrays.stream(BusinessErrorCode.values()).map(BusinessErrorCode::getCode).toList();
    }

    @Test
    void values_shouldFollowTheBusinessCodeFormat() {
        assertThat(codes()).allMatch(code -> code.matches("^EVT-(BUS|VAL|REQ)-\\d{3}$"));
    }

    @Test
    void values_shouldHaveUniqueCodes() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    void values_shouldAnswerAClientError() {
        assertThat(BusinessErrorCode.values())
                .allMatch(error -> error.getHttpStatus().is4xxClientError(), "answers 4xx");
    }

    @Test
    void values_shouldNotReuseACodeTheHandlerWritesItself() {
        assertThat(codes()).doesNotContainAnyElementsOf(GlobalErrorHandler.HANDLER_CODES);
    }

    @Test
    void values_shouldNotOverlapTechnicalCodes() {
        Set<String> technical = Arrays.stream(TechnicalErrorCode.values())
                .map(TechnicalErrorCode::getCode)
                .collect(Collectors.toSet());

        assertThat(codes()).doesNotContainAnyElementsOf(technical);
    }
}
