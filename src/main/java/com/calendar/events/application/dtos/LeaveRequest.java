package com.calendar.events.application.dtos;

/**
 * @param newOrganizerId who takes over, required when the organizer leaves others behind
 * @param deleteIfLast   confirms the deletion when the last member leaves
 */
public record LeaveRequest(String newOrganizerId, boolean deleteIfLast) {}
