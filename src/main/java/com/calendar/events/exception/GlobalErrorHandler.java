package com.calendar.events.exception;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns every failure into an RFC 7807 {@code application/problem+json} response, built by {@link Problems}.
 *
 * <p>Each response and each log line carries the WebFlux request id, so a client quoting it leads to
 * the right log lines. Technical failures are also counted, by code, so a dependency outage shows on a
 * dashboard before anyone reads a log.
 */
@Slf4j
@RestControllerAdvice
public class GlobalErrorHandler {

    /** Bean Validation rejections. Written here as a literal: no enum value stands for it. */
    public static final String VALIDATION_CODE = "EVT-VAL-001";
    /** Statuses the framework decided (unknown path, wrong method, unreadable body). */
    public static final String REQUEST_CODE = "EVT-REQ-000";
    /** The catch-all: a failure nothing translated. */
    public static final String UNEXPECTED_CODE = "EVT-TEC-000";
    /** Every code the handler writes without an enum behind it. */
    public static final List<String> LITERAL_CODES = List.of(VALIDATION_CODE, REQUEST_CODE, UNEXPECTED_CODE);

    /** Exported by Prometheus as {@code wely_errors_technical_total{code="..."}}. */
    public static final String TECHNICAL_ERRORS_METRIC = "wely.errors.technical";
    public static final String CODE_TAG = "code";

    private final MeterRegistry meterRegistry;

    public GlobalErrorHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusinessException(BusinessException ex, ServerWebExchange exchange) {
        BusinessErrorCode error = ex.getErrorCode();

        // Expected outcome, not an incident: logged at WARN, and without a stack trace.
        log.warn("Business failure {} on {} (request {})", error.getCode(), exchange.getRequest().getPath(),
                exchange.getRequest().getId());

        ProblemDetail problem = problem(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(),
                exchange);
        ex.getProperties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(TechnicalException.class)
    public ProblemDetail handleTechnicalException(TechnicalException ex, ServerWebExchange exchange) {
        TechnicalErrorCode error = ex.getErrorCode();

        // A dependency is down: ERROR with the cause, because someone has to act on it.
        // The previous version logged these at WARN as business failures, which hid them.
        log.error("Technical failure {} on {} (request {})", error.getCode(), exchange.getRequest().getPath(),
                exchange.getRequest().getId(), ex);
        countTechnicalFailure(meterRegistry, error.getCode());

        return problem(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(),
                exchange);
    }

    /** A multipart upload past the codec's limit, before any controller could cap it itself. */
    @ExceptionHandler(DataBufferLimitException.class)
    public ProblemDetail handleDataBufferLimitException(DataBufferLimitException ex, ServerWebExchange exchange) {
        return handleBusinessException(new BusinessException(BusinessErrorCode.MEDIA_TOO_LARGE), exchange);
    }

    /** Bean Validation rejections, with the offending fields listed. */
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidationException(WebExchangeBindException ex,
                                                  ServerWebExchange exchange) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        // A rejected request, not an incident: WARN, like a business failure.
        log.warn("Validation failure {} on {} (request {})", VALIDATION_CODE, exchange.getRequest().getPath(),
                exchange.getRequest().getId());

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_CODE,
                "Invalid request", "One or more fields are invalid.", exchange);
        problem.setProperty("errors", fieldErrors);

        return problem;
    }

    /**
     * Statuses the framework itself decided: an unknown path, a method that does not apply, a
     * body it cannot read.
     *
     * <p>Without this they fall through to the catch-all below and every one of them answers 500.
     * A missing page reported as a server fault is wrong twice over — it misleads the caller, and
     * it fills the logs with "unhandled failure" for requests that were handled exactly as they
     * should have been.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex,
                                                       ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        log.debug("{} on {} (request {})", status, exchange.getRequest().getPath(), exchange.getRequest().getId());

        return problem(status, REQUEST_CODE, status.getReasonPhrase(),
                "The request could not be served as sent.", exchange);
    }

    /**
     * Last resort. The detail is deliberately generic: an exception message can carry a
     * query, a constraint name or a host, and none of that belongs in a response. The real
     * message goes to the logs, where the timestamp ties the two together.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled failure on {} (request {})", exchange.getRequest().getPath(),
                exchange.getRequest().getId(), ex);
        countTechnicalFailure(meterRegistry, UNEXPECTED_CODE);

        return problem(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_CODE,
                "Unexpected error", "The request could not be completed.", exchange);
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail,
                                  ServerWebExchange exchange) {
        return Problems.of(status, code, title, detail, exchange.getRequest().getPath().value(),
                exchange.getRequest().getId());
    }

    /** Shared with the filters that answer a technical failure before any controller, so they count it too. */
    public static void countTechnicalFailure(MeterRegistry meterRegistry, String code) {
        meterRegistry.counter(TECHNICAL_ERRORS_METRIC, CODE_TAG, code).increment();
    }
}
