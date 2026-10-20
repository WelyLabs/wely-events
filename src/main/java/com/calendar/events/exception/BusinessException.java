package com.calendar.events.exception;

import lombok.Getter;

import java.util.Map;

/**
 * Business failure raised by the domain layer. Carries no framework type so the
 * domain stays independent of Spring; the HTTP translation happens in
 * {@link GlobalErrorHandler}. A dependency that is down raises a {@link TechnicalException}
 * instead.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final BusinessErrorCode errorCode;
    /** Extra ProblemDetail properties, e.g. the id of what the request collided with. */
    private final Map<String, Object> properties;

    public BusinessException(BusinessErrorCode errorCode) {
        this(errorCode, Map.of());
    }

    public BusinessException(BusinessErrorCode errorCode, Map<String, Object> properties) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
        this.properties = Map.copyOf(properties);
    }
}
