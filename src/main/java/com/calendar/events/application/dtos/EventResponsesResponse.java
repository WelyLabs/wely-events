package com.calendar.events.application.dtos;

import java.util.List;

/** Member ids by answer. Everyone taking part is a yes, the organizer first. */
public record EventResponsesResponse(List<String> yes, List<String> maybe, List<String> no) {
}
