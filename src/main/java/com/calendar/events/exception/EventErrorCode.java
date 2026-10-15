package com.calendar.events.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Outcomes this service refuses on, each with the stable code a client branches on. */
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

    COVER_INVALID(
            "EVT-BUS-011",
            "Invalid cover",
            "The cover must be a JPEG, PNG, GIF or BMP image, sent as the multipart part 'file'.",
            HttpStatus.BAD_REQUEST),

    /** Shared by covers and receipts: the limit is the same. */
    MEDIA_TOO_LARGE(
            "EVT-BUS-012",
            "Picture too large",
            "A picture may weigh at most 10 MB.",
            HttpStatus.CONTENT_TOO_LARGE),

    RECEIPT_INVALID(
            "EVT-BUS-013",
            "Invalid receipt",
            "The receipt must be a JPEG, PNG, GIF or BMP image, sent as the multipart part 'file'.",
            HttpStatus.BAD_REQUEST),

    POLL_INVALID(
            "EVT-BUS-014",
            "Invalid date poll",
            "Propose 2 to 5 distinct slots, none in the past, each ending after it starts.",
            HttpStatus.BAD_REQUEST),

    POLL_CLOSED(
            "EVT-BUS-015",
            "Date poll closed",
            "This event has no date poll open: its date is already fixed.",
            HttpStatus.CONFLICT),

    POLL_SLOT_UNKNOWN(
            "EVT-BUS-016",
            "Unknown slot",
            "Answer only for slots this event's poll proposes.",
            HttpStatus.BAD_REQUEST),

    SCHEDULE_INVALID(
            "EVT-BUS-017",
            "Invalid schedule",
            "Give a start and an end after it, over at most 62 days; a new date cannot be in the past.",
            HttpStatus.BAD_REQUEST),

    SCHEDULE_LOCKED(
            "EVT-BUS-018",
            "Date poll open",
            "This event's date is still being voted on: fix it in the poll instead.",
            HttpStatus.CONFLICT),

    SCHEDULE_CHANGED(
            "EVT-BUS-019",
            "Date changed meanwhile",
            "Someone changed this event's date since it was read; reload it and try again.",
            HttpStatus.CONFLICT),

    NEW_ORGANIZER_REQUIRED(
            "EVT-BUS-020",
            "New organizer required",
            "The organizer cannot leave while others take part: name one of them as the new organizer.",
            HttpStatus.CONFLICT),

    NEW_ORGANIZER_INVALID(
            "EVT-BUS-021",
            "Invalid new organizer",
            "The new organizer must be someone else taking part in the event.",
            HttpStatus.BAD_REQUEST),

    LAST_MEMBER(
            "EVT-BUS-022",
            "Last member",
            "Leaving would delete the event; confirm the deletion.",
            HttpStatus.CONFLICT),

    NOT_A_COMMUNITY_EVENT(
            "EVT-BUS-023",
            "Not a community event",
            "Only an event published in a community takes Yes, Maybe or No answers; join or leave this one instead.",
            HttpStatus.CONFLICT),

    COMMUNITY_MEMBERS_ONLY(
            "EVT-BUS-024",
            "Members only",
            "Only members of the community can see or publish its events.",
            HttpStatus.FORBIDDEN),

    ACCOUNT_DELETED(
            "EVT-BUS-025",
            "Account deleted",
            "This account has been deleted: it can no longer change anything.",
            HttpStatus.GONE),

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

    // EVT-TEC-004 is reserved for the wely-users client of the reconciliation.

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
