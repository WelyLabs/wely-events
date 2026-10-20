package com.calendar.events.exception;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalErrorCounterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TechnicalErrorCounter counter = new TechnicalErrorCounter(meterRegistry);

    private double counted(String code, String source) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, source).count();
    }

    @Test
    void count_shouldCountATechnicalFailureUnderItsCodeAndSource() {
        counter.count(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE), GlobalErrorHandler.SOURCE_OUTBOX);
        counter.count(new TechnicalException(TechnicalErrorCode.BROKER_UNAVAILABLE), GlobalErrorHandler.SOURCE_OUTBOX);

        assertThat(counted("EVT-TEC-002", GlobalErrorHandler.SOURCE_OUTBOX)).isEqualTo(2.0);
        assertThat(counted("EVT-TEC-002", GlobalErrorHandler.SOURCE_HTTP)).isZero();
    }

    @Test
    void count_shouldCountAnythingElseAsUnexpected() {
        counter.count(new IllegalStateException("mongo down"), GlobalErrorHandler.SOURCE_JOB);

        assertThat(counted(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE, GlobalErrorHandler.SOURCE_JOB)).isEqualTo(1.0);
    }

    @Test
    void count_shouldNotCountABusinessFailure() {
        counter.count(new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND), GlobalErrorHandler.SOURCE_KAFKA);

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).isEmpty();
    }

    @Test
    void count_shouldCountARetryExhaustionAsTheFailureItGaveUpOn() {
        counter.count(Exceptions.retryExhausted("5 retries", new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE)),
                GlobalErrorHandler.SOURCE_KAFKA);
        counter.count(Exceptions.retryExhausted("5 retries", new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND)),
                GlobalErrorHandler.SOURCE_KAFKA);

        assertThat(meterRegistry.find(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC).counters()).hasSize(1);
        assertThat(counted("EVT-TEC-005", GlobalErrorHandler.SOURCE_KAFKA)).isEqualTo(1.0);
    }
}
