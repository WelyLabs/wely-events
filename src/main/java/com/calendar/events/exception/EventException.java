package com.calendar.events.exception;

import lombok.Getter;

import java.util.Map;

/**
 * Business failure raised by the domain layer. Carries no framework type so the
 * domain stays independent of Spring; the HTTP translation happens in
 * {@link GlobalErrorHandler}.
 */
@Getter
public class EventException extends RuntimeException {

    private final EventErrorCode errorCode;
    /** Extra ProblemDetail properties, e.g. the id of what the request collided with. */
    private final Map<String, Object> properties;

    public EventException(EventErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public EventException(EventErrorCode errorCode, Map<String, Object> properties) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
        this.properties = Map.copyOf(properties);
    }
}
