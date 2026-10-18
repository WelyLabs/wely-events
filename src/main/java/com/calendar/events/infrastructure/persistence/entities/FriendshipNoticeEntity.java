package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A friendship notice, outbox and idempotency record at once, as {@link BirthdayNoticeEntity}: the id is
 * the notice's key. Sent ones stay until the TTL, so the nudge caps can read who was nudged lately.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "friendship_notices")
@CompoundIndex(name = "recipient_type_recorded", def = "{'recipientId': 1, 'type': 1, 'recordedAt': -1}")
public class FriendshipNoticeEntity {
    /** {@code REUNION_NUDGE:recipientId:day} or {@code FRIENDSHIP_ANNIVERSARY:recipientId:friendId:years}. */
    @Id
    private String id;
    private String type;
    private String recipientId;
    private String friendId;
    private String friendName;
    private String closeness;
    private Instant lastSharedAt;
    private String lastSharedEventId;
    private Integer monthsApart;
    private Integer years;
    private Instant since;
    private LocalDate day;
    /** A year and a month: past every key's reuse, and every cap's window. */
    @Indexed(expireAfter = "400d")
    private Instant recordedAt;
    /** False until the relay has sent it. */
    @Indexed
    private boolean published;
}
