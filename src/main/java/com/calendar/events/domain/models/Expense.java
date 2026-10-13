package com.calendar.events.domain.models;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Money someone paid for an event, and who it was for.
 *
 * <p>Amounts are whole cents in a {@code long}: a {@code double} cannot hold 0.10 exactly, and a
 * split must add up to the expense to the cent. Every check lives here, so no expense that does not
 * add up can exist, whoever builds it.
 */
public record Expense(String id, String eventId, String title, long amountCents, String currency,
                      String paidBy, String createdBy, ExpenseKind kind, SplitMode split,
                      List<Share> shares, Instant spentAt, Long version) {

    /** The only currency for now; stored so a second one does not need a migration. */
    public static final String CURRENCY = "EUR";
    /** A million euros: past this it is a typo, not a weekend. */
    public static final long MAX_AMOUNT_CENTS = 100_000_000L;
    public static final int MAX_TITLE_LENGTH = 80;
    public static final int MAX_SHARES = 50;
    public static final long MAX_WEIGHT = 100;
    public static final String SETTLEMENT_TITLE = "Remboursement";

    public Expense {
        boolean valid = eventId != null && paidBy != null && createdBy != null && kind != null
                && split != null && shares != null && spentAt != null
                && title != null && !title.isBlank() && title.length() <= MAX_TITLE_LENGTH
                && amountCents > 0 && amountCents <= MAX_AMOUNT_CENTS
                && !shares.isEmpty() && shares.size() <= MAX_SHARES
                && sharesAreSound(shares, split, amountCents);
        if (!valid) {
            throw new EventException(EventErrorCode.EXPENSE_INVALID);
        }
        title = title.strip();
        currency = currency == null ? CURRENCY : currency;
        shares = List.copyOf(shares);
    }

    /** Money handed back: {@code from} pays, wholly for {@code to}. */
    public static Expense settlement(String eventId, String fromUserId, String toUserId, long amountCents,
                                     String recordedBy, Instant at) {
        if (Objects.equals(fromUserId, toUserId)) {
            throw new EventException(EventErrorCode.EXPENSE_INVALID);
        }
        return new Expense(null, eventId, SETTLEMENT_TITLE, amountCents, CURRENCY, fromUserId, recordedBy,
                ExpenseKind.SETTLEMENT, SplitMode.EXACT, List.of(new Share(toUserId, amountCents)), at, null);
    }

    /** Everyone this expense names: who paid and who it was for. */
    public Set<String> people() {
        Set<String> people = new HashSet<>();
        people.add(paidBy);
        shares.forEach(share -> people.add(share.userId()));
        return people;
    }

    /** What each person owes for this expense, in cents, in the order of the shares; always adds up to the amount. */
    public Map<String, Long> owedBy() {
        Map<String, Long> owed = new LinkedHashMap<>();
        switch (split) {
            case EXACT -> shares.forEach(share -> owed.put(share.userId(), share.value()));
            case EQUAL -> {
                shares.forEach(share -> owed.put(share.userId(), amountCents / shares.size()));
                distribute(owed, amountCents % shares.size(), byUserId(shares));
            }
            case SHARES -> {
                long totalWeight = shares.stream().mapToLong(Share::value).sum();
                shares.forEach(share -> owed.put(share.userId(), amountCents * share.value() / totalWeight));
                long leftover = amountCents - owed.values().stream().mapToLong(Long::longValue).sum();
                // Largest remainder first, so the cents go to whoever was rounded down the most.
                List<Share> byRemainder = shares.stream()
                        .sorted(Comparator.comparingLong((Share share) -> amountCents * share.value() % totalWeight)
                                .reversed()
                                .thenComparing(Share::userId))
                        .toList();
                distribute(owed, leftover, byRemainder);
            }
        }
        return owed;
    }

    private static void distribute(Map<String, Long> owed, long cents, List<Share> order) {
        for (int i = 0; i < cents; i++) {
            owed.merge(order.get(i).userId(), 1L, Long::sum);
        }
    }

    private static List<Share> byUserId(List<Share> shares) {
        return shares.stream().sorted(Comparator.comparing(Share::userId)).toList();
    }

    private static boolean sharesAreSound(List<Share> shares, SplitMode split, long amountCents) {
        if (shares.stream().anyMatch(share -> share == null || share.userId() == null || share.userId().isBlank())) {
            return false;
        }
        boolean distinct = shares.stream().map(Share::userId).distinct().count() == shares.size();
        return distinct && switch (split) {
            case EQUAL -> true;
            case SHARES -> shares.stream().allMatch(share -> share.value() > 0 && share.value() <= MAX_WEIGHT);
            case EXACT -> shares.stream().allMatch(share -> share.value() >= 0)
                    && shares.stream().mapToLong(Share::value).sum() == amountCents;
        };
    }
}
