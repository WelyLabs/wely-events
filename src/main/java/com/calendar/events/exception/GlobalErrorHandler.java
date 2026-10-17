package com.calendar.events.exception;

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
import java.util.Map;

/** Turns every failure into an RFC 7807 {@code application/problem+json} response, built by {@link Problems}. */
@Slf4j
@RestControllerAdvice
public class GlobalErrorHandler {

    @ExceptionHandler(EventException.class)
    public ProblemDetail handleEventException(EventException ex, ServerWebExchange exchange) {
        EventErrorCode error = ex.getErrorCode();

        // Expected outcome, not an incident: logged at WARN, and without a stack trace.
        log.warn("Business failure {} on {}", error.getCode(), exchange.getRequest().getPath());

        ProblemDetail problem = problem(error.getHttpStatus(), error.getCode(), error.getTitle(), error.getDetail(),
                exchange);
        ex.getProperties().forEach(problem::setProperty);
        return problem;
    }

    /** A multipart upload past the codec's limit, before any controller could cap it itself. */
    @ExceptionHandler(DataBufferLimitException.class)
    public ProblemDetail handleDataBufferLimitException(DataBufferLimitException ex, ServerWebExchange exchange) {
        return handleEventException(new EventException(EventErrorCode.MEDIA_TOO_LARGE), exchange);
    }

    /** Bean Validation rejections, with the offending fields listed. */
    @ExceptionHandler(WebExchangeBindException.class)
    public ProblemDetail handleValidationException(WebExchangeBindException ex,
                                                  ServerWebExchange exchange) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "EVT-VAL-001",
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
        log.debug("{} on {}", status, exchange.getRequest().getPath());

        return problem(status, "EVT-REQ-000", status.getReasonPhrase(),
                "The request could not be served as sent.", exchange);
    }

    /**
     * Last resort. The detail is deliberately generic: an exception message can carry a
     * query, a constraint name or a host, and none of that belongs in a response. The real
     * message goes to the logs, where the timestamp ties the two together.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled failure on {}", exchange.getRequest().getPath(), ex);

        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "EVT-TEC-000",
                "Unexpected error", "The request could not be completed.", exchange);
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail,
                                  ServerWebExchange exchange) {
        return Problems.of(status, code, title, detail, exchange.getRequest().getPath().value());
    }
}
