package com.calendar.events.domain.models;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where an event's money stands: what each person is owed (positive) or owes (negative), and a
 * short list of payments that would settle it all.
 *
 * <p>The payments pair the largest debt with the largest credit until nothing is left, which takes
 * at most one payment fewer than there are people. Not always the very fewest, but close, and
 * always the same answer for the same expenses.
 */
public record Ledger(long totalCents, Map<String, Long> balances, List<Transfer> transfers) {

    public Ledger {
        balances = Map.copyOf(balances);
        transfers = List.copyOf(transfers);
    }

    public static Ledger of(List<Expense> expenses) {
        Map<String, Long> balances = new TreeMap<>();
        long total = 0;
        for (Expense expense : expenses) {
            if (expense.kind() == ExpenseKind.EXPENSE) {
                total += expense.amountCents();
            }
            balances.merge(expense.paidBy(), expense.amountCents(), Long::sum);
            expense.owedBy().forEach((userId, owed) -> balances.merge(userId, -owed, Long::sum));
        }
        return new Ledger(total, balances, settle(balances));
    }

    private static List<Transfer> settle(Map<String, Long> balances) {
        List<long[]> creditors = new ArrayList<>();
        List<long[]> debtors = new ArrayList<>();
        List<String> ids = new ArrayList<>(balances.keySet());
        for (int i = 0; i < ids.size(); i++) {
            long balance = balances.get(ids.get(i));
            if (balance > 0) {
                creditors.add(new long[] {i, balance});
            } else if (balance < 0) {
                debtors.add(new long[] {i, -balance});
            }
        }
        // Largest first; ties in id order, which the TreeMap already gives.
        Comparator<long[]> largest = Comparator.comparingLong((long[] entry) -> entry[1]).reversed()
                .thenComparingLong(entry -> entry[0]);
        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            creditors.sort(largest);
            debtors.sort(largest);
            long[] creditor = creditors.getFirst();
            long[] debtor = debtors.getFirst();
            long amount = Math.min(creditor[1], debtor[1]);
            transfers.add(new Transfer(ids.get((int) debtor[0]), ids.get((int) creditor[0]), amount));
            creditor[1] -= amount;
            debtor[1] -= amount;
            if (creditor[1] == 0) {
                creditors.removeFirst();
            }
            if (debtor[1] == 0) {
                debtors.removeFirst();
            }
        }
        return transfers;
    }
}
