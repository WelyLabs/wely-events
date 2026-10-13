package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.SplitMode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** An expense, with what each person owes for it already worked out to the cent. */
public record ExpenseResponse(
        String id,
        String title,
        long amountCents,
        String currency,
        String paidBy,
        String createdBy,
        ExpenseKind kind,
        SplitMode split,
        List<ShareResponse> shares,
        Map<String, Long> owedCents,
        Instant spentAt,
        Long version
) {
    public record ShareResponse(String userId, long value) {}
}
