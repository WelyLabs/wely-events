package com.calendar.events.infrastructure.messaging.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** wely-users' {@code USER_BIRTHDAY} record: day and month only, never the year. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserBirthdayEventDTO(String userId, String displayName, Integer month, Integer day) {
}
