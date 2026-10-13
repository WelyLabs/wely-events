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
            "Free slots and calendars are only available for your friends.",
            HttpStatus.FORBIDDEN),

    INVALID_SLOT_SEARCH(
            "EVT-BUS-004",
            "Invalid slot search",
            "Pick 1 to 20 friends, a duration of at least 15 minutes that fits the period, and a period of at most 62 days.",
            HttpStatus.BAD_REQUEST),

    INVALID_CALENDAR_REQUEST(
            "EVT-BUS-005",
            "Invalid calendar request",
            "Ask for a friend's calendar, not your own, over a period of at most 62 days that ends after it starts.",
            HttpStatus.BAD_REQUEST),

    INVALID_MAP_AREA(
            "EVT-BUS-006",
            "Invalid map area",
            "Ask for an area within the globe, south before north and west before east, at most 90 degrees wide.",
            HttpStatus.BAD_REQUEST),

    EXPENSE_NOT_FOUND(
            "EVT-BUS-007",
            "Expense not found",
            "No expense of this event matches the given identifier.",
            HttpStatus.NOT_FOUND),

    EXPENSE_INVALID(
            "EVT-BUS-008",
            "Invalid expense",
            "An expense needs a title, an amount up to 1,000,000.00, a payer and people who are members of the event, and a split that adds up to the amount.",
            HttpStatus.BAD_REQUEST),

    EXPENSE_ACCESS_DENIED(
            "EVT-BUS-009",
            "Not the expense's author",
            "Only whoever recorded an expense, or the organizer, may change or delete it; a settlement only involves the two people it moves money between.",
            HttpStatus.FORBIDDEN),

    EXPENSE_CONFLICT(
            "EVT-BUS-010",
            "Expense changed meanwhile",
            "Someone changed this expense since it was read; reload it and try again.",
            HttpStatus.CONFLICT),

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
            HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
