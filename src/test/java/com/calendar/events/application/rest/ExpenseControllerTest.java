package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ExpenseRequest;
import com.calendar.events.application.dtos.ExpenseResponse;
import com.calendar.events.application.dtos.LedgerResponse;
import com.calendar.events.application.dtos.SettlementRequest;
import com.calendar.events.application.dtos.ShareRequest;
import com.calendar.events.application.mappers.ExpenseRestMapper;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseDraft;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Ledger;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.ExpenseService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.exception.EventErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mapstruct.factory.Mappers;
import com.calendar.events.exception.EventException;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
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
    private MediaStore mediaStore;

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
        controller = new ExpenseController(expenseService, new PictureService(mediaStore), mapper);
        when(jwt.getClaimAsString("businessId")).thenReturn("alice");
    }

    @Test
    void list_shouldAskForTheCallersView() {
        when(expenseService.list("ev-1", "alice")).thenReturn(Flux.just(expense));
        when(mapper.toResponse(expense, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.list("ev-1", jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void add_shouldRecordTheExpenseAsTheCaller() {
        when(mapper.toDraft(request)).thenReturn(draft);
        when(expenseService.add("ev-1", "alice", draft)).thenReturn(Mono.just(expense));
        when(mapper.toResponse(expense, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.add("ev-1", request, jwt)).expectNext(response).verifyComplete();
    }

    @Test
    void update_shouldChangeTheExpenseAsTheCaller() {
        when(mapper.toDraft(request)).thenReturn(draft);
        when(expenseService.update("ev-1", "x1", "alice", draft)).thenReturn(Mono.just(expense));
        when(mapper.toResponse(expense, SignedUrls.NONE)).thenReturn(response);

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
        when(mapper.toResponse(expense, SignedUrls.NONE)).thenReturn(response);

        StepVerifier.create(controller.settle("ev-1", new SettlementRequest("bob", "alice", 1000), jwt))
                .expectNext(response)
                .verifyComplete();
    }

    private static Expense withReceipt(String id, String receiptKey) {
        return new Expense(id, "ev-1", "Courses", 1000, "EUR", "alice", "alice", ExpenseKind.EXPENSE, SplitMode.EQUAL,
                List.of(new Share("alice", 0)), Instant.EPOCH, receiptKey, 0L);
    }

    @Test
    void list_shouldSignEveryReceiptInOneCall() {
        Expense first = withReceipt("x1", "events/ev-1/receipts/x1-a.jpg");
        Expense second = withReceipt("x2", "events/ev-1/receipts/x2-a.jpg");
        Expense third = withReceipt("x3", null);
        when(expenseService.list("ev-1", "alice")).thenReturn(Flux.just(first, second, third));
        when(mediaStore.urls(List.of("events/ev-1/receipts/x1-a.jpg", "events/ev-1/receipts/x2-a.jpg")))
                .thenReturn(Mono.just(Map.of("events/ev-1/receipts/x1-a.jpg", "https://media/1",
                        "events/ev-1/receipts/x2-a.jpg", "https://media/2")));
        ExpenseController realMapping = new ExpenseController(expenseService, new PictureService(mediaStore),
                Mappers.getMapper(ExpenseRestMapper.class));

        StepVerifier.create(realMapping.list("ev-1", jwt).map(expense -> String.valueOf(expense.receiptUrl())))
                .expectNext("https://media/1", "https://media/2", "null")
                .verifyComplete();
        verify(mediaStore, times(1)).urls(any());
    }

    @Test
    void list_shouldAnswerWithoutReceiptUrls_whenWelyMediaIsDown() {
        when(expenseService.list("ev-1", "alice")).thenReturn(Flux.just(withReceipt("x1", "events/ev-1/receipts/x1-a.jpg")));
        when(mediaStore.urls(any())).thenReturn(Mono.error(new EventException(EventErrorCode.MEDIA_UNAVAILABLE)));
        ExpenseController realMapping = new ExpenseController(expenseService, new PictureService(mediaStore),
                Mappers.getMapper(ExpenseRestMapper.class));

        StepVerifier.create(realMapping.list("ev-1", jwt))
                .expectNextMatches(expense -> expense.id().equals("x1") && expense.receiptUrl() == null)
                .verifyComplete();
    }

    @Test
    void attachReceipt_shouldStoreTheFileAsTheCaller_andAnswerTheSignedExpense() {
        Expense attached = withReceipt("x1", "events/ev-1/receipts/x1-a.jpg");
        SignedUrls urls = new SignedUrls(Map.of("events/ev-1/receipts/x1-a.jpg", "https://media/1"));
        when(expenseService.attachReceipt(eq("ev-1"), eq("x1"), eq("alice"), any())).thenReturn(Mono.just(attached));
        when(mediaStore.urls(List.of("events/ev-1/receipts/x1-a.jpg"))).thenReturn(Mono.just(urls.byKey()));
        when(mapper.toResponse(attached, urls)).thenReturn(response);

        StepVerifier.create(controller.attachReceipt("ev-1", "x1", UploadsTest.form(UploadsTest.file(2048)), jwt))
                .expectNext(response)
                .verifyComplete();
        verify(expenseService).attachReceipt(eq("ev-1"), eq("x1"), eq("alice"), argThat(bytes -> bytes.length == 2048));
    }

    @Test
    void attachReceipt_shouldRefuseAFileOver10Megabytes_beforeTheService() {
        StepVerifier.create(controller.attachReceipt("ev-1", "x1", UploadsTest.form(UploadsTest.file(Uploads.MAX_BYTES + 1)), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, EventErrorCode.MEDIA_TOO_LARGE))
                .verify();
        verify(expenseService, never()).attachReceipt(any(), any(), any(), any());
    }

    @Test
    void attachReceipt_shouldRefuseAFormWithoutFile_asAnInvalidReceipt() {
        StepVerifier.create(controller.attachReceipt("ev-1", "x1", UploadsTest.form(null), jwt))
                .expectErrorMatches(error -> UploadsTest.failsWith(error, EventErrorCode.RECEIPT_INVALID))
                .verify();
    }

    @Test
    void removeReceipt_shouldRemoveAsTheCaller() {
        when(expenseService.removeReceipt("ev-1", "x1", "alice")).thenReturn(Mono.empty());

        StepVerifier.create(controller.removeReceipt("ev-1", "x1", jwt)).verifyComplete();

        verify(expenseService).removeReceipt("ev-1", "x1", "alice");
    }
}
