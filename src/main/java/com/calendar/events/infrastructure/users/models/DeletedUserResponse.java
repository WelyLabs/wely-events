package com.calendar.events.infrastructure.users.models;

import java.time.Instant;
import java.util.UUID;

/** One tombstone of the listing; a UUID type, so an id that could become a field name never gets in. */
public record DeletedUserResponse(UUID userId, Instant deletedAt) {
}
