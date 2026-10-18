package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One change applied to a pair; the id makes (pair, version) unique, so a replay appends nothing. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "friendship_history")
public class FriendshipHistoryEntity {
    /** {@code <pairId>:<version>}. */
    @Id
    private String id;
    private String pairId;
    /** The lower id; indexed, like {@code b}, so "this user's year" is two index lookups. */
    @Indexed
    private String a;
    @Indexed
    private String b;
    private String type;
    private String closeness;
    private String previousCloseness;
    private Instant at;
    private long version;
}
