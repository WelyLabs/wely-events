package com.calendar.events.application.dtos;

/**
 * @param newOrganizerId who takes over, required when the organizer leaves others behind
 * @param deleteIfLast   confirms the deletion when the last member leaves; optional, left out it means no
 */
public record LeaveRequest(String newOrganizerId, Boolean deleteIfLast) {

    /** Left out is no: a wrapper, so a body naming only the new organizer is still readable. */
    public boolean confirmsDeletion() {
        return Boolean.TRUE.equals(deleteIfLast);
    }
}
