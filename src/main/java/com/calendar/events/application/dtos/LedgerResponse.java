package com.calendar.events.application.dtos;

import java.util.List;

/** Where an event's money stands: each member's balance, and payments that would settle it. */
public record LedgerResponse(
        String currency,
        long totalCents,
        List<BalanceResponse> balances,
        List<TransferResponse> transfers
) {
    /** Positive: owed money. Negative: owes it. */
    public record BalanceResponse(String userId, long netCents) {}

    public record TransferResponse(String fromUserId, String toUserId, long amountCents) {}
}
