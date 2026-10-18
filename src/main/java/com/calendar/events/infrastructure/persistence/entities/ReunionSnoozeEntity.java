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

/** A friend a user asked not to be reminded of; Mongo drops it once {@code until} passes. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "reunion_snoozes")
public class ReunionSnoozeEntity {
    /** {@code userId:friendId}: one snooze per pair and direction, a new one replaces it. */
    @Id
    private String id;
    @Indexed
    private String userId;
    @Indexed
    private String friendId;
    @Indexed(expireAfter = "0s")
    private Instant until;
}
