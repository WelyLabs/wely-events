package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ExpenseRequest;
import com.calendar.events.application.dtos.ExpenseResponse;
import com.calendar.events.application.dtos.LedgerResponse;
import com.calendar.events.application.dtos.SettlementRequest;
import com.calendar.events.application.dtos.ShareRequest;
import com.calendar.events.application.mappers.ExpenseRestMapper;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseDraft;
import com.calendar.events.domain.models.Ledger;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.services.ExpenseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The controller only translates: the identity comes from the token, everything else from the service. */
@ExtendWith(MockitoExtension.class)
class ExpenseControllerTest {

    @Mock
    private ExpenseService expenseService;

    @Mock
    private ExpenseRestMapper mapper;

    @Mock
    private Jwt jwt;

    private ExpenseController controller;

    private final ExpenseRequest request = new ExpenseRequest("Courses", 3000, "alice", SplitMode.EQUAL,
            List.of(new ShareRequest("alice", 0)), null, null);
    private final ExpenseDraft draft = mock(ExpenseDraft.class);
    private final Expense expense = mock(Expense.class);
    private final ExpenseResponse response = mock(ExpenseResponse.class);

    @BeforeEach
    void setUp() {
        controller = new ExpenseController(expenseService, mapper);
        when(jwt.getClaimAsString("businessId")).thenReturn("alice");
    }

    @Test
    void list_shouldAskForTheCallersView() {
        when(expenseService.list("ev-1", "alice")).thenReturn(Flux.just(expense));
        when(mapper.toResponse(expense)).thenReturn(response);

        StepVerifier.create(controller.list("ev-1", jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void add_shouldRecordTheExpenseAsTheCaller() {
        when(mapper.toDraft(request)).thenReturn(draft);
        when(expenseService.add("ev-1", "alice", draft)).thenReturn(Mono.just(expense));
        when(mapper.toResponse(expense)).thenReturn(response);

        StepVerifier.create(controller.add("ev-1", request, jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void update_shouldChangeTheExpenseAsTheCaller() {
        when(mapper.toDraft(request)).thenReturn(draft);
        when(expenseService.update("ev-1", "x1", "alice", draft)).thenReturn(Mono.just(expense));
        when(mapper.toResponse(expense)).thenReturn(response);

        StepVerifier.create(controller.update("ev-1", "x1", request, jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void delete_shouldDeleteAsTheCaller() {
        when(expenseService.delete("ev-1", "x1", "alice")).thenReturn(Mono.empty());

        StepVerifier.create(controller.delete("ev-1", "x1", jwt)).verifyComplete();

        verify(expenseService).delete("ev-1", "x1", "alice");
    }

    @Test
    void ledger_shouldAskForTheCallersView() {
        Ledger ledger = new Ledger(0, Map.of(), List.of());
        LedgerResponse ledgerResponse = new LedgerResponse("EUR", 0, List.of(), List.of());
        when(expenseService.ledger("ev-1", "alice")).thenReturn(Mono.just(ledger));
        when(mapper.toResponse(ledger)).thenReturn(ledgerResponse);

        StepVerifier.create(controller.ledger("ev-1", jwt)).expectNext(ledgerResponse).verifyComplete();
    }

    @Test
    void settle_shouldRecordTheRepaymentAsTheCaller() {
        when(expenseService.settle("ev-1", "alice", "bob", "alice", 1000)).thenReturn(Mono.just(expense));
        when(mapper.toResponse(expense)).thenReturn(response);

        StepVerifier.create(controller.settle("ev-1", new SettlementRequest("bob", "alice", 1000), jwt))
                .expectNext(response)
                .verifyComplete();
    }
}
