package com.calendar.events.exception;

import lombok.Getter;

/**
 * A dependency this service needs is unavailable.
 *
 * <p>Distinct from {@link BusinessException} because the two call for different responses:
 * a business failure means the caller should change the request, a technical one means the
 * caller should retry and someone should look at an incident.
 */
@Getter
public class TechnicalException extends RuntimeException {

    private final TechnicalErrorCode errorCode;

    public TechnicalException(TechnicalErrorCode errorCode) {
        super(errorCode.getDetail());
        this.errorCode = errorCode;
    }

    /** Keeps the dependency's own error as the cause, so the ERROR log shows what really broke. */
    public TechnicalException(TechnicalErrorCode errorCode, Throwable cause) {
        super(errorCode.getDetail(), cause);
        this.errorCode = errorCode;
    }
}
