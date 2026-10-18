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

/** One pair, as wely-social's FRIENDSHIP_STATE last described it; kept, not friends, once they part. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "friendships")
public class FriendshipEntity {
    /** {@code <a>:<b>}, the two businessIds sorted. */
    @Id
    private String id;
    /** The lower id; indexed, like {@code b}, so "this user's friends" is two index lookups. */
    @Indexed
    private String a;
    @Indexed
    private String b;
    private boolean friends;
    /** FRIEND, CLOSE or INTIMATE; null when they are not friends. */
    private String closeness;
    /** When they became friends; null when they are not. */
    private Instant since;
    /** wely-social's version of the pair: a record not above it is stale. */
    private long version;
    /** The version of the last FRIENDSHIP_STATE value read for the pair, stale or not; null before the first. */
    private Long streamedVersion;
}
