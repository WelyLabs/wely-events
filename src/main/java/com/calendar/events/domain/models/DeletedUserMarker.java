package com.calendar.events.domain.models;

import java.time.Instant;

/** An account wely-users deleted, kept here forever; {@code erasedAt} stays null until an erasure completes. */
public record DeletedUserMarker(String userId, Instant deletedAt, Instant erasedAt) {
}
