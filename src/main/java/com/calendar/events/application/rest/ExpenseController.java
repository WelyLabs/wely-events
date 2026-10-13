package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ExpenseRequest;
import com.calendar.events.application.dtos.ExpenseResponse;
import com.calendar.events.application.dtos.LedgerResponse;
import com.calendar.events.application.dtos.SettlementRequest;
import com.calendar.events.application.mappers.ExpenseRestMapper;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.MediaProfile;
import com.calendar.events.domain.services.ExpenseService;
import com.calendar.events.domain.services.PictureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/** An event's shared expenses. Translates HTTP only: every rule lives in {@link ExpenseService}. */
@RestController
@RequestMapping("events/{eventId}/expenses")
public class ExpenseController {

    private static final String BUSINESS_ID = "businessId";

    private final ExpenseService expenseService;
    private final PictureService pictureService;
    private final ExpenseRestMapper mapper;

    public ExpenseController(ExpenseService expenseService, PictureService pictureService, ExpenseRestMapper mapper) {
        this.expenseService = expenseService;
        this.pictureService = pictureService;
        this.mapper = mapper;
    }

    @GetMapping
    public Flux<ExpenseResponse> list(@PathVariable String eventId, @AuthenticationPrincipal Jwt jwt) {
        return respond(expenseService.list(eventId, jwt.getClaimAsString(BUSINESS_ID)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ExpenseResponse> add(@PathVariable String eventId, @Valid @RequestBody ExpenseRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        return respond(expenseService.add(eventId, jwt.getClaimAsString(BUSINESS_ID), mapper.toDraft(request)));
    }

    @PutMapping("/{expenseId}")
    public Mono<ExpenseResponse> update(@PathVariable String eventId, @PathVariable String expenseId,
                                        @Valid @RequestBody ExpenseRequest request, @AuthenticationPrincipal Jwt jwt) {
        return respond(expenseService.update(eventId, expenseId, jwt.getClaimAsString(BUSINESS_ID),
                mapper.toDraft(request)));
    }

    @DeleteMapping("/{expenseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String eventId, @PathVariable String expenseId,
                             @AuthenticationPrincipal Jwt jwt) {
        return expenseService.delete(eventId, expenseId, jwt.getClaimAsString(BUSINESS_ID));
    }

    @GetMapping("/ledger")
    public Mono<LedgerResponse> ledger(@PathVariable String eventId, @AuthenticationPrincipal Jwt jwt) {
        return expenseService.ledger(eventId, jwt.getClaimAsString(BUSINESS_ID)).map(mapper::toResponse);
    }

    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ExpenseResponse> settle(@PathVariable String eventId, @Valid @RequestBody SettlementRequest request,
                                        @AuthenticationPrincipal Jwt jwt) {
        return respond(expenseService.settle(eventId, jwt.getClaimAsString(BUSINESS_ID), request.fromUserId(),
                request.toUserId(), request.amountCents()));
    }

    /** Multipart {@code file}; same rights as an edit. 413 EVT-BUS-012 above 10 MB, 400 EVT-BUS-013 for anything but an image. */
    @PutMapping(value = "/{expenseId}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ExpenseResponse> attachReceipt(@PathVariable String eventId, @PathVariable String expenseId,
                                               @RequestBody Mono<MultiValueMap<String, Part>> form,
                                               @AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getClaimAsString(BUSINESS_ID);
        return respond(Uploads.fileOf(form, MediaProfile.RECEIPT)
                .flatMap(content -> expenseService.attachReceipt(eventId, expenseId, userId, content)));
    }

    @DeleteMapping("/{expenseId}/receipt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> removeReceipt(@PathVariable String eventId, @PathVariable String expenseId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return expenseService.removeReceipt(eventId, expenseId, jwt.getClaimAsString(BUSINESS_ID));
    }

    /** One signing round for the whole list. */
    private Flux<ExpenseResponse> respond(Flux<Expense> expenses) {
        return expenses.collectList()
                .flatMapMany(list -> pictureService.signReceipts(list)
                        .flatMapIterable(urls -> list.stream().map(expense -> mapper.toResponse(expense, urls)).toList()));
    }

    private Mono<ExpenseResponse> respond(Mono<Expense> expense) {
        return expense.flatMap(found -> pictureService.signReceipts(List.of(found))
                .map(urls -> mapper.toResponse(found, urls)));
    }
}
