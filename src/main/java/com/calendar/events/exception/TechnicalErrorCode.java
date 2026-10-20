package com.calendar.events.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Failures of a dependency rather than of the request.
 *
 * <p>Each names which dependency broke, so an incident can be routed without reading the
 * logs. They answer 502 rather than 500: the request was valid and this service is
 * running — something it depends on is not, and 502 says exactly that. The one exception is
 * ERASURE_INCOMPLETE, 503: nothing is down, the store kept moving under a background erasure
 * that is simply tried again.
 */
@Getter
@AllArgsConstructor
public enum TechnicalErrorCode {

    SOCIAL_UNAVAILABLE(
            "EVT-TEC-001",
            "Social service unavailable",
            "Friendships could not be checked; try again shortly.",
            HttpStatus.BAD_GATEWAY),

    /** Raised by the group relay in the background: no request waits on it, the next pass retries. */
    BROKER_UNAVAILABLE(
            "EVT-TEC-002",
            "Message broker unavailable",
            "The event's members could not be sent to Kafka; they will be sent again shortly.",
            HttpStatus.BAD_GATEWAY),

    MEDIA_UNAVAILABLE(
            "EVT-TEC-003",
            "Media service unavailable",
            "The picture could not be stored; try again shortly.",
            HttpStatus.BAD_GATEWAY),

    /** Raised by the reconciliation in the background: no request waits on it, the next pass retries. */
    USERS_UNAVAILABLE(
            "EVT-TEC-004",
            "Users service unavailable",
            "Deleted accounts could not be listed from wely-users; the next reconciliation pass retries.",
            HttpStatus.BAD_GATEWAY),

    STORE_UNAVAILABLE(
            "EVT-TEC-005",
            "Database unavailable",
            "The deleted-accounts register could not be read or written; try again shortly.",
            HttpStatus.BAD_GATEWAY),

    /** Raised by the account erasure in the background: no request waits on it, the erasure is retried. */
    ERASURE_INCOMPLETE(
            "EVT-TEC-006",
            "Account erasure incomplete",
            "An event kept changing while a deleted account was being erased; the erasure is retried.",
            HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
