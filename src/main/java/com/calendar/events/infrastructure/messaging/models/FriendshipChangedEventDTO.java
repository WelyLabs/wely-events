package com.calendar.events.infrastructure.messaging.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * wely-social's {@code FRIENDSHIP_CHANGED} record. {@code closeness} is the pair's after the change, null
 * when they are not friends; {@code version} rises with every change of the pair. Type and closeness stay
 * strings, so an unknown value drops the record instead of failing the read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FriendshipChangedEventDTO(String type, String fromUserId, String toUserId, String closeness,
                                        Instant since, Long version) {
}
