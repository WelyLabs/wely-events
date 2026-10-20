package com.calendar.events.exception;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.Exceptions;

/**
 * The one place that counts a technical failure, as {@code wely.errors.technical{code, source}}: the HTTP
 * paths and the background ones (consumers, relays, reconciliation, jobs) alike, so the tags cannot drift.
 *
 * <p>A {@link BusinessException} is not counted: a refused request or record is an expected outcome, not
 * an incident. Anything that is neither business nor technical counts as {@code EVT-TEC-000}.
 */
@Component
public class TechnicalErrorCounter {

    private final MeterRegistry meterRegistry;

    public TechnicalErrorCounter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Counts {@code error} under {@code source}, one of the {@code SOURCE_*} values of {@link GlobalErrorHandler}.
     * A retry exhaustion is counted as the failure it gave up on.
     */
    public void count(Throwable error, String source) {
        Throwable failure = Exceptions.isRetryExhausted(error) && error.getCause() != null ? error.getCause() : error;
        if (failure instanceof BusinessException) {
            return;
        }
        String code = failure instanceof TechnicalException technical
                ? technical.getErrorCode().getCode()
                : GlobalErrorHandler.UNEXPECTED_FAILURE_CODE;
        meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC,
                GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, source).increment();
    }
}
