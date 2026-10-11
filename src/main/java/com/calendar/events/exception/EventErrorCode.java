package com.calendar.events.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business outcomes this service refuses on, each with a stable code.
 *
 * <p>{@code code} is the contract a client branches on; {@code title} and {@code detail}
 * are English, for whoever reads the response or the logs.
 */
@Getter
@AllArgsConstructor
public enum EventErrorCode {

    EVENT_NOT_FOUND(
            "EVT-BUS-001",
            "Event not found",
            "No event matches the given identifier.",
            HttpStatus.NOT_FOUND),

    EVENT_ACCESS_DENIED(
            "EVT-BUS-002",
            "Not the organizer",
            "Only the organizer may perform this action.",
            HttpStatus.FORBIDDEN),

    NOT_A_FRIEND(
            "EVT-BUS-003",
            "Not a friend",
            "Free slots can only be searched with your friends.",
            HttpStatus.FORBIDDEN),

    INVALID_SLOT_SEARCH(
            "EVT-BUS-004",
            "Invalid slot search",
            "Pick 1 to 20 friends, a duration of at least 15 minutes that fits the period, and a period of at most 31 days.",
            HttpStatus.BAD_REQUEST),

    SOCIAL_UNAVAILABLE(
            "EVT-TEC-001",
            "Social service unavailable",
            "Friendships could not be checked; try again shortly.",
            HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
