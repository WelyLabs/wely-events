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

/** A calendar link handed out; MongoDB drops it once expired (the TTL monitor runs every minute). */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "calendar_links")
public class CalendarLinkEntity {
    /** The token itself: looked up by _id only. */
    @Id
    private String id;
    private String eventId;
    private String pageUrl;
    @Indexed(expireAfter = "0s")
    private Instant expiresAt;
}
