package com.calendar.events.domain.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerTest {

    private static final Instant AT = Instant.parse("2026-10-13T10:00:00Z");

    private static Expense equalSplit(String paidBy, long amountCents, String... people) {
        return new Expense(null, "ev-1", "Dépense", amountCents, null, paidBy, paidBy, ExpenseKind.EXPENSE,
                SplitMode.EQUAL, Arrays.stream(people).map(id -> new Share(id, 0)).toList(), AT, null, null);
    }

    @Test
    @DisplayName("Alice pays 30.00 for three: Bob and Carol each owe her 10.00")
    void of_shouldComputeBalancesAndTransfers() {
        Ledger ledger = Ledger.of(List.of(equalSplit("alice", 3000, "alice", "bob", "carol")));

        assertThat(ledger.totalCents()).isEqualTo(3000);
        assertThat(ledger.balances()).containsEntry("alice", 2000L).containsEntry("bob", -1000L).containsEntry("carol", -1000L);
        assertThat(ledger.transfers()).containsExactly(
                new Transfer("bob", "alice", 1000), new Transfer("carol", "alice", 1000));
    }

    @Test
    void of_shouldNetOppositeDebts() {
        // Each pays 20.00 for both: nobody owes anything.
        Ledger ledger = Ledger.of(List.of(equalSplit("alice", 2000, "alice", "bob"), equalSplit("bob", 2000, "alice", "bob")));

        assertThat(ledger.balances()).containsEntry("alice", 0L).containsEntry("bob", 0L);
        assertThat(ledger.transfers()).isEmpty();
    }

    @Test
    void of_shouldSettleWithAtMostOneTransferFewerThanPeople() {
        Ledger ledger = Ledger.of(List.of(
                equalSplit("alice", 6000, "alice", "bob", "carol", "dan"),
                equalSplit("bob", 2000, "alice", "bob", "carol", "dan"),
                equalSplit("carol", 1000, "carol", "dan")));

        assertThat(ledger.transfers()).hasSizeLessThanOrEqualTo(3);
        // Applying the transfers brings every balance to zero.
        Map<String, Long> after = new HashMap<>(ledger.balances());
        ledger.transfers().forEach(transfer -> {
            after.merge(transfer.fromUserId(), transfer.amountCents(), Long::sum);
            after.merge(transfer.toUserId(), -transfer.amountCents(), Long::sum);
        });
        assertThat(after.values()).allMatch(balance -> balance == 0);
    }

    @Test
    void of_shouldClearTheDebt_onceASettlementIsRecorded() {
        Ledger ledger = Ledger.of(List.of(
                equalSplit("alice", 2000, "alice", "bob"),
                Expense.settlement("ev-1", "bob", "alice", 1000, "bob", AT)));

        assertThat(ledger.balances()).containsEntry("alice", 0L).containsEntry("bob", 0L);
        assertThat(ledger.transfers()).isEmpty();
        // A settlement is money handed back, not spent: the total does not count it.
        assertThat(ledger.totalCents()).isEqualTo(2000);
    }

    @Test
    void of_shouldBeEmpty_withoutExpenses() {
        Ledger ledger = Ledger.of(List.of());

        assertThat(ledger.totalCents()).isZero();
        assertThat(ledger.balances()).isEmpty();
        assertThat(ledger.transfers()).isEmpty();
    }
}
