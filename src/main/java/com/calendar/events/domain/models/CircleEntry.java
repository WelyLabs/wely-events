package com.calendar.events.domain.models;

/** One entry of the circle calendar: an intimate friend's calendar entry, and whose it is. */
public record CircleEntry(String userId, CalendarEntry entry) {
}
