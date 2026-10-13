package com.calendar.events.domain.models;

import java.time.Instant;
import java.util.List;

/**
 * What a member writes when adding or changing an expense. The event, the author and the kind
 * are not theirs to choose; {@code version} is the one they read, so a stale edit is refused.
 */
public record ExpenseDraft(String title, long amountCents, String paidBy, SplitMode split,
                           List<Share> shares, Instant spentAt, Long version) {}
