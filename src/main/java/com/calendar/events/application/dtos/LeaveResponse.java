package com.calendar.events.application.dtos;

/** @param event the event after the leave; null when the last member left and it was deleted */
public record LeaveResponse(boolean deleted, EventResponse event) {}
