package com.calendar.events.exception;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Answers our problem for a failure raised outside any controller — in a web filter, or before a handler
 * was even chosen — which {@link GlobalErrorHandler} never sees.
 *
 * <p>Without it such a failure reaches Boot's {@code DefaultErrorWebExceptionHandler} and answers Boot's
 * own JSON, without {@code code} or {@code requestId}. The mapping, the log line and the counter are
 * {@link GlobalErrorHandler}'s, called as they are, so the two paths cannot drift apart.
 */
@Component
@Order(ProblemWebExceptionHandler.ORDER)
public class ProblemWebExceptionHandler implements WebExceptionHandler {

    /** Before Boot's DefaultErrorWebExceptionHandler (-1) and WebFlux's WebFluxResponseStatusExceptionHandler (0). */
    static final int ORDER = -2;

    private final GlobalErrorHandler errorHandler;
    private final ProblemWriter problemWriter;

    public ProblemWebExceptionHandler(GlobalErrorHandler errorHandler, ProblemWriter problemWriter) {
        this.errorHandler = errorHandler;
        this.problemWriter = problemWriter;
    }

    /**
     * A response already on its way cannot be replaced, and a JVM {@link Error} is not ours to answer:
     * both are passed on unchanged.
     */
    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted() || !(ex instanceof Exception exception)) {
            return Mono.error(ex);
        }
        return problemWriter.write(exchange, problemFor(exception, exchange));
    }

    /** The same dispatch as the advice's {@code @ExceptionHandler} methods, most specific type first. */
    ProblemDetail problemFor(Exception ex, ServerWebExchange exchange) {
        return switch (ex) {
            case BusinessException business -> errorHandler.handleBusinessException(business, exchange);
            case TechnicalException technical -> errorHandler.handleTechnicalException(technical, exchange);
            case DataBufferLimitException tooLarge -> errorHandler.handleDataBufferLimitException(tooLarge, exchange);
            case WebExchangeBindException invalid -> errorHandler.handleValidationException(invalid, exchange);
            case ResponseStatusException status -> errorHandler.handleResponseStatusException(status, exchange);
            default -> errorHandler.handleUnexpectedException(ex, exchange);
        };
    }
}
