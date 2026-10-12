package com.calendar.events.application.dtos;

import java.util.List;

public record ParticipantsResponse(int count, List<String> participantIds, List<String> friendIds) {}
