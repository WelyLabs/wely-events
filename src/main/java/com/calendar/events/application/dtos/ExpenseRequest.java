package com.calendar.events.application.dtos;

import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.SplitMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * An expense as a member writes it. {@code version} is the one they read when editing, so a change
 * made meanwhile by someone else is refused rather than silently overwritten.
 */
public record ExpenseRequest(
        @NotBlank @Size(max = Expense.MAX_TITLE_LENGTH) String title,
        @Positive @Max(Expense.MAX_AMOUNT_CENTS) long amountCents,
        @NotBlank String paidBy,
        @NotNull SplitMode split,
        @NotEmpty @Size(max = Expense.MAX_SHARES) List<@Valid @NotNull ShareRequest> shares,
        Instant spentAt,
        Long version
) {}
