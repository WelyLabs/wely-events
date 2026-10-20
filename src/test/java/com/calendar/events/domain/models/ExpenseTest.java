package com.calendar.events.domain.models;

import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpenseTest {

    private static final Instant AT = Instant.parse("2026-10-13T10:00:00Z");

    private static Expense expense(long amountCents, SplitMode split, List<Share> shares) {
        return new Expense(null, "ev-1", "Courses", amountCents, null, "alice", "alice",
                ExpenseKind.EXPENSE, split, shares, AT, null, null, null);
    }

    private static void assertInvalid(Runnable build) {
        assertThatThrownBy(build::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(BusinessErrorCode.EXPENSE_INVALID));
    }

    @Test
    @DisplayName("10.00 in three equal parts: 3.34, 3.33, 3.33, the extra cent to the first id")
    void owedBy_shouldSpreadTheLeftoverCents_whenEqualDoesNotDivide() {
        Expense expense = expense(1000, SplitMode.EQUAL,
                List.of(new Share("carol", 0), new Share("alice", 0), new Share("bob", 0)));

        assertThat(expense.owedBy()).containsExactly(
                Map.entry("carol", 333L), Map.entry("alice", 334L), Map.entry("bob", 333L));
    }

    @Test
    void owedBy_shouldWeighEachShare_whenSplitByShares() {
        // A couple counts double: 2/3 and 1/3 of 9.00.
        Expense expense = expense(900, SplitMode.SHARES, List.of(new Share("couple", 2), new Share("bob", 1)));

        assertThat(expense.owedBy()).containsExactly(Map.entry("couple", 600L), Map.entry("bob", 300L));
    }

    @Test
    void owedBy_shouldGiveTheLeftoverToTheLargestRemainder_whenSharesDoNotDivide() {
        // 1.00 over weights 1, 1, 1: 33.33… each, one cent left, to the first id among equal remainders.
        Expense expense = expense(100, SplitMode.SHARES,
                List.of(new Share("b", 1), new Share("a", 1), new Share("c", 1)));

        Map<String, Long> owed = expense.owedBy();

        assertThat(owed).containsEntry("a", 34L).containsEntry("b", 33L).containsEntry("c", 33L);
    }

    @Test
    void owedBy_shouldAlwaysAddUpToTheAmount() {
        Expense equal = expense(1_000_001, SplitMode.EQUAL,
                List.of(new Share("a", 0), new Share("b", 0), new Share("c", 0), new Share("d", 0), new Share("e", 0), new Share("f", 0), new Share("g", 0)));
        Expense weighted = expense(999_999, SplitMode.SHARES,
                List.of(new Share("a", 3), new Share("b", 7), new Share("c", 11)));

        assertThat(equal.owedBy().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1_000_001);
        assertThat(weighted.owedBy().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(999_999);
    }

    @Test
    void owedBy_shouldUseTheGivenAmounts_whenExact() {
        Expense expense = expense(1250, SplitMode.EXACT, List.of(new Share("alice", 1000), new Share("bob", 250)));

        assertThat(expense.owedBy()).containsExactly(Map.entry("alice", 1000L), Map.entry("bob", 250L));
    }

    @Test
    void constructor_shouldRefuseExactAmounts_whenTheyDoNotAddUp() {
        assertInvalid(() -> expense(1250, SplitMode.EXACT, List.of(new Share("alice", 1000), new Share("bob", 200))));
    }

    @Test
    void constructor_shouldRefuseANonPositiveOrOversizedAmount() {
        assertInvalid(() -> expense(0, SplitMode.EQUAL, List.of(new Share("alice", 0))));
        assertInvalid(() -> expense(Expense.MAX_AMOUNT_CENTS + 1, SplitMode.EQUAL, List.of(new Share("alice", 0))));
    }

    @Test
    void constructor_shouldRefuseNoOneOrTheSamePersonTwice() {
        assertInvalid(() -> expense(100, SplitMode.EQUAL, List.of()));
        assertInvalid(() -> expense(100, SplitMode.EQUAL, List.of(new Share("alice", 0), new Share("alice", 0))));
    }

    @Test
    void constructor_shouldRefuseAZeroOrHugeWeight() {
        assertInvalid(() -> expense(100, SplitMode.SHARES, List.of(new Share("alice", 0))));
        assertInvalid(() -> expense(100, SplitMode.SHARES, List.of(new Share("alice", Expense.MAX_WEIGHT + 1))));
    }

    @Test
    void constructor_shouldRefuseABlankOrLongTitle() {
        assertInvalid(() -> new Expense(null, "ev-1", "  ", 100, null, "alice", "alice",
                ExpenseKind.EXPENSE, SplitMode.EQUAL, List.of(new Share("alice", 0)), AT, null, null, null));
        assertInvalid(() -> new Expense(null, "ev-1", "x".repeat(Expense.MAX_TITLE_LENGTH + 1), 100, null, "alice",
                "alice", ExpenseKind.EXPENSE, SplitMode.EQUAL, List.of(new Share("alice", 0)), AT, null, null, null));
    }

    @Test
    void constructor_shouldDefaultTheCurrencyAndTrimTheTitle() {
        Expense expense = new Expense(null, "ev-1", "  Essence ", 100, null, "alice", "alice",
                ExpenseKind.EXPENSE, SplitMode.EQUAL, List.of(new Share("alice", 0)), AT, null, null, null);

        assertThat(expense.currency()).isEqualTo("EUR");
        assertThat(expense.title()).isEqualTo("Essence");
    }

    @Test
    void settlement_shouldBePaidByTheDebtorWhollyForTheCreditor() {
        Expense settlement = Expense.settlement("ev-1", "bob", "alice", 500, "bob", AT);

        assertThat(settlement.kind()).isEqualTo(ExpenseKind.SETTLEMENT);
        assertThat(settlement.paidBy()).isEqualTo("bob");
        assertThat(settlement.owedBy()).containsExactly(Map.entry("alice", 500L));
    }

    @Test
    void settlement_shouldRefuseSomeonePayingThemselves() {
        assertInvalid(() -> Expense.settlement("ev-1", "bob", "bob", 500, "bob", AT));
    }

    @Test
    void people_shouldNameThePayerAndEveryoneItWasFor() {
        Expense expense = expense(100, SplitMode.EQUAL, List.of(new Share("bob", 0), new Share("carol", 0)));

        assertThat(expense.people()).containsExactlyInAnyOrder("alice", "bob", "carol");
    }

    @Test
    void newReceiptKey_shouldBeUniqueUnderTheEventsPrefix() {
        String first = Expense.newReceiptKey("ev-1", "x1");

        assertThat(first).matches("events/ev-1/receipts/x1-[0-9a-f-]{36}\\.jpg");
        assertThat(Expense.newReceiptKey("ev-1", "x1")).isNotEqualTo(first);
    }

    @Test
    void settlement_shouldHaveNoReceipt() {
        assertThat(Expense.settlement("ev-1", "bob", "alice", 500, "bob", AT).receiptKey()).isNull();
    }
}
