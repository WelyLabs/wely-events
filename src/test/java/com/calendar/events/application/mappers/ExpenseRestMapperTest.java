package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.ExpenseRequest;
import com.calendar.events.application.dtos.ExpenseResponse;
import com.calendar.events.application.dtos.LedgerResponse;
import com.calendar.events.application.dtos.ShareRequest;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseDraft;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Ledger;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.models.Transfer;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseRestMapperTest {

    private final ExpenseRestMapper mapper = Mappers.getMapper(ExpenseRestMapper.class);

    @Test
    void toDraft_shouldCarryEveryField() {
        Instant at = Instant.parse("2026-10-13T10:00:00Z");

        ExpenseDraft draft = mapper.toDraft(new ExpenseRequest("Courses", 3000L, "alice", SplitMode.SHARES,
                List.of(new ShareRequest("alice", 2L), new ShareRequest("bob", 1L)), at, 4L));

        assertThat(draft).isEqualTo(new ExpenseDraft("Courses", 3000, "alice", SplitMode.SHARES,
                List.of(new Share("alice", 2), new Share("bob", 1)), at, 4L));
    }

    @Test
    void toResponse_shouldIncludeWhatEachPersonOwes() {
        Expense expense = new Expense("x1", "ev-1", "Courses", 1000, "EUR", "alice", "alice", ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share("alice", 0), new Share("bob", 0), new Share("carol", 0)),
                Instant.parse("2026-10-13T10:00:00Z"), null, null, 1L);

        ExpenseResponse response = mapper.toResponse(expense, SignedUrls.NONE);

        assertThat(response.id()).isEqualTo("x1");
        assertThat(response.owedCents()).containsEntry("alice", 334L).containsEntry("bob", 333L).containsEntry("carol", 333L);
        assertThat(response.shares()).hasSize(3);
        assertThat(response.version()).isEqualTo(1L);
    }

    @Test
    void toResponse_shouldListBalancesInIdOrder_inEuros() {
        Ledger ledger = new Ledger(3000, Map.of("bob", -1000L, "alice", 1000L), List.of(new Transfer("bob", "alice", 1000)));

        LedgerResponse response = mapper.toResponse(ledger);

        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.totalCents()).isEqualTo(3000);
        assertThat(response.balances()).containsExactly(
                new LedgerResponse.BalanceResponse("alice", 1000), new LedgerResponse.BalanceResponse("bob", -1000));
        assertThat(response.transfers()).containsExactly(new LedgerResponse.TransferResponse("bob", "alice", 1000));
    }

    @Test
    void toResponse_shouldShowTheReceiptThroughItsSignedUrl_andNeverItsKey() {
        Expense expense = new Expense("x1", "ev-1", "Courses", 1000, "EUR", "alice", "alice", ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share("alice", 0)), Instant.parse("2026-10-13T10:00:00Z"),
                "events/ev-1/receipts/x1-a.jpg", null, 1L);

        assertThat(mapper.toResponse(expense, new SignedUrls(Map.of("events/ev-1/receipts/x1-a.jpg", "https://media/r")))
                .receiptUrl()).isEqualTo("https://media/r");
        assertThat(mapper.toResponse(expense, SignedUrls.NONE).receiptUrl()).isNull();
    }
}
