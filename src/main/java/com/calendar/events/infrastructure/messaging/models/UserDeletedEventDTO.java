package com.calendar.events.infrastructure.messaging.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** wely-users' {@code USER_DELETED} record; {@code deletedAt} is absent from records older than the field. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserDeletedEventDTO(String userId, Instant deletedAt) {
}
