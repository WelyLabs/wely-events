package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.ErasureReport;
import com.calendar.events.domain.services.AccountErasureService;
import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserDeletedConsumerTest {

    private static final String USER = "89a380b8-f66a-44d0-b9d9-616241080430";
    private static final String OTHER = "1b4e28ba-2fa1-41d2-883f-0016d3cca427";
    private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:03:27.123Z");

    @Mock
    private AccountErasureService erasure;

    private UserDeletedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UserDeletedConsumer(erasure, JsonMapper.builder().build());
    }

    private static Message<byte[]> record(String json) {
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static Message<byte[]> deletion(String userId) {
        return record("{\"userId\":\"" + userId + "\",\"deletedAt\":\"" + DELETED_AT + "\"}");
    }

    @SafeVarargs
    private Mono<Void> consume(Message<byte[]>... records) {
        return consumer.userDeleted().apply(Flux.just(records));
    }

    @Test
    void userDeleted_shouldEraseTheUserNamedByTheRecord() {
        when(erasure.erase(USER, DELETED_AT)).thenReturn(Mono.just(ErasureReport.NONE));

        StepVerifier.create(consume(deletion(USER))).verifyComplete();
        verify(erasure).erase(USER, DELETED_AT);
    }

    @Test
    void userDeleted_shouldReadDeletedAtAsAnIsoInstant() {
        when(erasure.erase(USER, Instant.parse("2026-10-15T14:03:27Z"))).thenReturn(Mono.just(ErasureReport.NONE));

        StepVerifier.create(consume(record("{\"userId\":\"" + USER + "\",\"deletedAt\":\"2026-10-15T14:03:27Z\",\"reason\":\"x\"}")))
                .verifyComplete();
        verify(erasure).erase(USER, Instant.parse("2026-10-15T14:03:27Z"));
    }

    @Test
    void userDeleted_shouldPassANullDeletedAt_forAnOlderRecord() {
        when(erasure.erase(eq(USER), isNull())).thenReturn(Mono.just(ErasureReport.NONE));

        StepVerifier.create(consume(record("{\"userId\":\"" + USER + "\"}"))).verifyComplete();
        verify(erasure).erase(USER, null);
    }

    @Test
    void userDeleted_shouldLowercaseTheUserId() {
        when(erasure.erase(USER, DELETED_AT)).thenReturn(Mono.just(ErasureReport.NONE));

        StepVerifier.create(consume(deletion(USER.toUpperCase()))).verifyComplete();
        verify(erasure).erase(USER, DELETED_AT);
    }

    @Test
    void userDeleted_shouldSkipAnUnreadableRecord_andGoOn() {
        when(erasure.erase(USER, DELETED_AT)).thenReturn(Mono.just(ErasureReport.NONE));

        StepVerifier.create(consume(record("not json"), record("{\"deletedAt\":\"yesterday\"}"), deletion(USER)))
                .verifyComplete();
        verify(erasure).erase(USER, DELETED_AT);
        verify(erasure, times(1)).erase(anyString(), any());
    }

    @Test
    void userDeleted_shouldSkipARecordWhoseUserIdIsNotACanonicalUuid() {
        StepVerifier.create(consume(deletion("1-1-1-1-1"), deletion("lea.$x"), record("{\"deletedAt\":\"" + DELETED_AT + "\"}")))
                .verifyComplete();
        verify(erasure, never()).erase(any(), any());
    }

    @Test
    void userDeleted_shouldRetryAFailedErasure_thenSucceed() {
        TechnicalException down = new TechnicalException(TechnicalErrorCode.STORE_UNAVAILABLE);
        when(erasure.erase(USER, DELETED_AT)).thenReturn(Mono.error(down), Mono.error(down), Mono.just(ErasureReport.NONE));

        StepVerifier.withVirtualTime(() -> consume(deletion(USER)))
                .thenAwait(Duration.ofMinutes(5))
                .verifyComplete();
        verify(erasure, times(3)).erase(USER, DELETED_AT);
    }

    @Test
    void userDeleted_shouldLogAndGoOn_whenErasureStillFailsAfterItsRetries() {
        when(erasure.erase(USER, DELETED_AT)).thenReturn(Mono.error(new TechnicalException(TechnicalErrorCode.ERASURE_INCOMPLETE)));
        when(erasure.erase(OTHER, DELETED_AT)).thenReturn(Mono.just(ErasureReport.NONE));

        StepVerifier.withVirtualTime(() -> consume(deletion(USER), deletion(OTHER)))
                .thenAwait(Duration.ofMinutes(5))
                .verifyComplete();
        verify(erasure, times(1 + UserDeletedConsumer.MAX_RETRIES)).erase(USER, DELETED_AT);
        verify(erasure).erase(OTHER, DELETED_AT);
    }

    @Test
    void userDeleted_shouldHandleRecordsOneAtATime_inOrder() {
        when(erasure.erase(USER, DELETED_AT))
                .thenAnswer(call -> Mono.delay(Duration.ofSeconds(1)).thenReturn(ErasureReport.NONE));
        when(erasure.erase(OTHER, DELETED_AT)).thenReturn(Mono.just(ErasureReport.NONE));

        StepVerifier.withVirtualTime(() -> consume(deletion(USER), deletion(OTHER)))
                .expectSubscription()
                .then(() -> verify(erasure, never()).erase(OTHER, DELETED_AT))
                .thenAwait(Duration.ofSeconds(1))
                .verifyComplete();
        InOrder order = inOrder(erasure);
        order.verify(erasure).erase(USER, DELETED_AT);
        order.verify(erasure).erase(OTHER, DELETED_AT);
    }

    @Test
    void userDeleted_shouldComplete_whenTheInputCompletes() {
        StepVerifier.create(consumer.userDeleted().apply(Flux.empty())).verifyComplete();
    }
}
