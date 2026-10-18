package com.calendar.events.infrastructure.messaging.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A value of wely-social's compacted {@code FRIENDSHIP_STATE}, keyed {@code a:b}: the pair while they are
 * friends. The end of a friendship is a tombstone (no value), never this record. Closeness stays a string,
 * so an unknown value drops the record instead of failing the read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FriendshipStateDTO(String a, String b, Boolean friends, String closeness, Instant since, Long version) {
}
