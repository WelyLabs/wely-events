package com.calendar.events.application.rest;

import com.calendar.events.application.dtos.ExpenseRequest;
import com.calendar.events.application.dtos.ExpenseResponse;
import com.calendar.events.application.dtos.LedgerResponse;
import com.calendar.events.application.dtos.SettlementRequest;
import com.calendar.events.application.mappers.ExpenseRestMapper;
import com.calendar.events.domain.services.ExpenseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

/** An event's shared expenses. Translates HTTP only: every rule lives in {@link ExpenseService}. */
@RestController
@RequestMapping("events/{eventId}/expenses")
public class ExpenseController {

    private static final String BUSINESS_ID = "businessId";

    private final ExpenseService expenseService;
    private final ExpenseRestMapper mapper;

    public ExpenseController(ExpenseService expenseService, ExpenseRestMapper mapper) {
        this.expenseService = expenseService;
        this.mapper = mapper;
    }

    @GetMapping
    public Flux<ExpenseResponse> list(@PathVariable String eventId, @AuthenticationPrincipal Jwt jwt) {
        return expenseService.list(eventId, jwt.getClaimAsString(BUSINESS_ID)).map(mapper::toResponse);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ExpenseResponse> add(@PathVariable String eventId, @Valid @RequestBody ExpenseRequest request,
                                     @AuthenticationPrincipal Jwt jwt) {
        return expenseService.add(eventId, jwt.getClaimAsString(BUSINESS_ID), mapper.toDraft(request))
                .map(mapper::toResponse);
    }

    @PutMapping("/{expenseId}")
    public Mono<ExpenseResponse> update(@PathVariable String eventId, @PathVariable String expenseId,
                                        @Valid @RequestBody ExpenseRequest request, @AuthenticationPrincipal Jwt jwt) {
        return expenseService.update(eventId, expenseId, jwt.getClaimAsString(BUSINESS_ID), mapper.toDraft(request))
                .map(mapper::toResponse);
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
        return expenseService.settle(eventId, jwt.getClaimAsString(BUSINESS_ID), request.fromUserId(),
                        request.toUserId(), request.amountCents())
                .map(mapper::toResponse);
    }
}
