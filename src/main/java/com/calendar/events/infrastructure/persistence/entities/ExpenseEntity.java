package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "expenses")
// An event's expenses, newest first: the only way they are read.
@CompoundIndex(name = "event_spent", def = "{'eventId': 1, 'spentAt': -1}")
public class ExpenseEntity {
    @Id
    private String id;
    private String eventId;
    private String title;
    private long amountCents;
    private String currency;
    private String paidBy;
    private String createdBy;
    /** EXPENSE or SETTLEMENT. */
    private String kind;
    /** EQUAL, SHARES or EXACT. */
    private String split;
    private List<ShareEntity> shares;
    private Instant spentAt;
    /** Optimistic locking: a save carrying an older version is refused instead of overwriting. */
    @Version
    private Long version;

    public record ShareEntity(String userId, long value) {}
}
