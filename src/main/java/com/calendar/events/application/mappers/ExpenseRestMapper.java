package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.ExpenseRequest;
import com.calendar.events.application.dtos.ExpenseResponse;
import com.calendar.events.application.dtos.LedgerResponse;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseDraft;
import com.calendar.events.domain.models.Ledger;
import com.calendar.events.domain.models.SignedUrls;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface ExpenseRestMapper {

    ExpenseDraft toDraft(ExpenseRequest request);

    @Mapping(target = "owedCents", expression = "java(expense.owedBy())")
    @Mapping(target = "receiptUrl", expression = "java(urls.urlOf(expense.receiptKey()))")
    ExpenseResponse toResponse(Expense expense, @Context SignedUrls urls);

    @Mapping(target = "currency", constant = Expense.CURRENCY)
    LedgerResponse toResponse(Ledger ledger);

    /** In user id order, so the same ledger always reads the same. */
    default List<LedgerResponse.BalanceResponse> toBalances(Map<String, Long> balances) {
        return balances.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new LedgerResponse.BalanceResponse(entry.getKey(), entry.getValue()))
                .toList();
    }
}
