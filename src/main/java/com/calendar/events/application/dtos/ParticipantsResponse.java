package com.calendar.events.application.dtos;

import java.util.List;

/**
 * {@code invitedIds}: every pending invitation for the organizer, the caller's own for an invitee,
 * none otherwise. {@code requestedIds}: who asked to join a surprise, with the same discretion --
 * the organizer sees them all, an asker only themselves.
 */
public record ParticipantsResponse(int count, List<String> participantIds, List<String> friendsGoingIds,
                                   List<String> invitedIds, List<String> requestedIds) {}
