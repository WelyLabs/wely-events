package com.calendar.events.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum EventErrorCode {

    EVENT_NOT_FOUND("EVT_BUS_001", "Event not found", HttpStatus.NOT_FOUND),
    EVENT_ACCESS_DENIED("EVT_BUS_002", "Only the organizer can perform this action", HttpStatus.FORBIDDEN);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
