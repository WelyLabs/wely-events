package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** A deleted event's tombstone: what wely-chat needs to close its group, kept until it is sent. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "event_deletions")
public class EventDeletionEntity {
    /** The deleted event's id: one tombstone per event. */
    @Id
    private String id;
    private String title;
    /** One past the event's last group version. */
    private long version;
    private Instant deletedAt;
}
