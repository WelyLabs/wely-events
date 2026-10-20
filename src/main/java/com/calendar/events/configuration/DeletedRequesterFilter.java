package com.calendar.events.configuration;

import com.calendar.events.domain.services.AccountGuard;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.ProblemWriter;
import com.calendar.events.exception.Problems;
import com.calendar.events.exception.TechnicalException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.Set;

/** Refuses every write from a deleted account with 410 EVT-BUS-025; not a bean, so it runs in the security chain only. */
@Slf4j
final class DeletedRequesterFilter implements WebFilter {

    static final Set<HttpMethod> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);
    static final String BUSINESS_ID_CLAIM = "businessId";

    private final AccountGuard accountGuard;
    private final ProblemWriter problemWriter;
    private final MeterRegistry meterRegistry;

    DeletedRequesterFilter(AccountGuard accountGuard, ProblemWriter problemWriter, MeterRegistry meterRegistry) {
        this.accountGuard = accountGuard;
        this.problemWriter = problemWriter;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (READS.contains(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }
        String path = exchange.getRequest().getPath().value();
        String requestId = exchange.getRequest().getId();
        // Optional, not switchIfEmpty: writing the refusal completes empty and would run the chain after it.
        return refusal(path, requestId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(refusal -> refusal.isPresent()
                        ? problemWriter.write(exchange, refusal.get())
                        : chain.filter(exchange));
    }

    /**
     * Why the requester may not write, as the body to answer with; empty when they may, or when
     * there is no JWT to read. A register that cannot be read refuses too, as a technical failure.
     */
    private Mono<ProblemDetail> refusal(String path, String requestId) {
        return ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .ofType(JwtAuthenticationToken.class)
                .mapNotNull(token -> token.getToken().getClaimAsString(BUSINESS_ID_CLAIM))
                .flatMap(accountGuard::isDeleted)
                .filter(Boolean::booleanValue)
                .map(deleted -> businessFailure(BusinessErrorCode.ACCOUNT_DELETED, path, requestId))
                .onErrorResume(BusinessException.class,
                        error -> Mono.just(businessFailure(error.getErrorCode(), path, requestId)))
                .onErrorResume(TechnicalException.class, error -> Mono.just(technicalFailure(error, path, requestId)));
    }

    // Logged and counted as GlobalErrorHandler does, which never sees these: the filter answers before any controller.
    private static ProblemDetail businessFailure(BusinessErrorCode error, String path, String requestId) {
        log.warn("Business failure {} on {} (request {})", error.getCode(), path, requestId);
        return Problems.of(error, path, requestId);
    }

    private ProblemDetail technicalFailure(TechnicalException ex, String path, String requestId) {
        String code = ex.getErrorCode().getCode();
        log.error("Technical failure {} on {} (request {})", code, path, requestId, ex);
        GlobalErrorHandler.countTechnicalFailure(meterRegistry, code);
        return Problems.of(ex.getErrorCode(), path, requestId);
    }
}
