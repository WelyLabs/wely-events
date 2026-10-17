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
import java.time.LocalDate;

/**
 * A birthday notice, outbox and idempotency record at once: the id is the notice's key, so the
 * same notice can never be recorded twice, and it stays once sent until the TTL drops it, past
 * any birthday it could be recorded again for.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "birthday_notices")
public class BirthdayNoticeEntity {
    /** {@code honoreeId:recipientId:year:type}. */
    @Id
    private String id;
    private String type;
    private String recipientId;
    private String honoreeId;
    private String honoreeName;
    private LocalDate date;
    private int daysUntil;
    private String closeness;
    private String surpriseEventId;
    private String organizerId;
    private String organizerName;
    /** A year and a month: the key's year has passed by then. */
    @Indexed(expireAfter = "400d")
    private Instant recordedAt;
    /** False until the relay has sent it. */
    @Indexed
    private boolean published;
}
