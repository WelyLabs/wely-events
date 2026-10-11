package com.calendar.events.application.dtos;

import java.time.Instant;

public record TimeRangeResponse(Instant start, Instant end) {}
