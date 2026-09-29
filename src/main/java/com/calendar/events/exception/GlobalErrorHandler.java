package com.calendar.events.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalErrorHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Mono<Map<String, String>> handleValidationExceptions(WebExchangeBindException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));
        return Mono.just(errors);
    }

    @ExceptionHandler(EventException.class)
    public Mono<ResponseEntity<ErrorResponse>> handleEventException(EventException ex) {
        EventErrorCode error = ex.getErrorCode();

        return Mono.just(ResponseEntity
                .status(error.getHttpStatus())
                .body(new ErrorResponse(error.getMessage(), error.getCode(), LocalDateTime.now())));
    }
}
