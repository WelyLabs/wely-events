package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Expense;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** Money handed back from one member to another. */
public record SettlementRequest(
        @NotBlank String fromUserId,
        @NotBlank String toUserId,
        @Positive @Max(Expense.MAX_AMOUNT_CENTS) long amountCents
) {}
