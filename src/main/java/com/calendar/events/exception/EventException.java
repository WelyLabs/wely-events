package com.calendar.events.exception;

import lombok.Getter;

/**
 * Business failure raised by the domain layer. Carries no framework type so the
 * domain stays independent of Spring; the HTTP translation happens in
 * {@link GlobalErrorHandler}.
 */
@Getter
public class EventException extends RuntimeException {

    private final EventErrorCode errorCode;

    public EventException(EventErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
