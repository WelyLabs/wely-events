package com.calendar.events.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Outcomes this service refuses on, each with the stable code a client branches on.
 *
 * <p>Only the request's own failures live here; a dependency that is down is a
 * {@link TechnicalErrorCode}.
 */
@Getter
@AllArgsConstructor
public enum BusinessErrorCode {

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
            "Give a start and an end after it, over at most 62 days.",
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

    DATE_NOT_FIXED(
            "EVT-BUS-026",
            "Date not fixed",
            "The date is still being voted on: the event cannot go into a calendar yet.",
            HttpStatus.CONFLICT),

    CALENDAR_LINK_EXPIRED(
            "EVT-BUS-027",
            "Calendar link expired",
            "This calendar link does not exist or has expired; add the event again from Wely.",
            HttpStatus.NOT_FOUND),

    SURPRISE_NOT_ALLOWED(
            "EVT-BUS-028",
            "Surprise not allowed",
            "Only a close or intimate friend of the honoree may organize a surprise for them, and never the honoree.",
            HttpStatus.FORBIDDEN),

    NO_BIRTHDAY(
            "EVT-BUS-029",
            "No birthday",
            "This person has not shared a birthday: there is no birthday to organize a surprise for.",
            HttpStatus.CONFLICT),

    /** Carries the existing surprise's id in the ProblemDetail property {@code eventId}. */
    SURPRISE_EXISTS(
            "EVT-BUS-030",
            "Surprise already organized",
            "A surprise is already organized for this birthday: join it instead.",
            HttpStatus.CONFLICT),

    EVENT_KIND_INVALID(
            "EVT-BUS-031",
            "Invalid event kind",
            "Create a STANDARD event, or a SURPRISE naming its honoreeId; birthdays come from the signup.",
            HttpStatus.BAD_REQUEST),

    INVALID_BIRTHDAY_RANGE(
            "EVT-BUS-032",
            "Invalid birthday range",
            "Ask for birthdays over a period of at most 400 days that ends after it starts.",
            HttpStatus.BAD_REQUEST),

    LOCATION_REQUIRED(
            "EVT-BUS-033",
            "Location required",
            "Every event but a surprise needs a location.",
            HttpStatus.BAD_REQUEST),

    ALL_DAY_INVALID(
            "EVT-BUS-034",
            "Invalid all-day event",
            "An all-day event starts at 00:00Z and ends at 00:00Z of the day after its last day, without a date poll.",
            HttpStatus.BAD_REQUEST),

    FEED_RANK_INVALID(
            "EVT-BUS-035",
            "Invalid feed rank",
            "Rank the feed by 'affinity', or leave the rank out for the usual order.",
            HttpStatus.BAD_REQUEST),

    /** 404 rather than 403: whether two people are friends is not the caller's to learn by guessing ids. */
    FRIEND_NOT_FOUND(
            "EVT-BUS-036",
            "Friend not found",
            "No friend of yours matches the given identifier.",
            HttpStatus.NOT_FOUND),

    INVALID_CIRCLE_REQUEST(
            "EVT-BUS-037",
            "Invalid circle request",
            "Ask for your circle over a period of at most 62 days that ends after it starts.",
            HttpStatus.BAD_REQUEST),

    EVENT_IN_PAST(
            "EVT-BUS-038",
            "Event in the past",
            "An event is created, moved or fixed on a date now or later; an all-day event, today or later.",
            HttpStatus.BAD_REQUEST),

    INVITATION_NOT_ALLOWED(
            "EVT-BUS-039",
            "Invitation not allowed",
            "Invite to an event of your own that is not over, outside a community, and never whoever it is kept from.",
            HttpStatus.CONFLICT),

    ALREADY_TAKING_PART(
            "EVT-BUS-040",
            "Already taking part",
            "This person already takes part in the event: there is nothing to invite them to.",
            HttpStatus.CONFLICT),

    TIME_RANGE_INVALID(
            "EVT-BUS-041",
            "Invalid time range",
            "Give both 'from' and 'to' as ISO-8601 instants, with 'to' after 'from'.",
            HttpStatus.BAD_REQUEST),

    /** Written by the security chain, not raised: the request carries no valid token. */
    AUTHENTICATION_REQUIRED(
            "EVT-REQ-001",
            "Authentication required",
            "Send a valid bearer token with the request.",
            HttpStatus.UNAUTHORIZED),

    /** Written by the security chain, not raised: the token is valid, the route is not for it. */
    ACCESS_DENIED(
            "EVT-REQ-002",
            "Access denied",
            "This token does not grant access to this route.",
            HttpStatus.FORBIDDEN);

    private final String code;
    private final String title;
    private final String detail;
    private final HttpStatus httpStatus;
}
