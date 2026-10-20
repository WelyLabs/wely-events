package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipChange;
import com.calendar.events.domain.models.FriendshipChangeType;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import com.calendar.events.infrastructure.messaging.models.FriendshipChangedEventDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipChangedConsumerTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private double technicalFailures(String code) {
        return meterRegistry.counter(GlobalErrorHandler.TECHNICAL_ERRORS_METRIC, GlobalErrorHandler.CODE_TAG, code,
                GlobalErrorHandler.SOURCE_TAG, GlobalErrorHandler.SOURCE_KAFKA).count();
    }

    // ALICE sorts before BOB.
    private static final String ALICE = "1b4e28ba-2fa1-41d2-883f-0016d3cca427";
    private static final String BOB = "89a380b8-f66a-44d0-b9d9-616241080430";
    private static final String ZOE = "c56a4180-65aa-42ec-a945-5fd21dec0538";
    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-10-18T08:00:00Z");

    @Mock
    private FriendshipService friendshipService;

    private FriendshipChangedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FriendshipChangedConsumer(friendshipService, JsonMapper.builder().build(), new TechnicalErrorCounter(meterRegistry));
    }

    private static Message<byte[]> record(String json) {
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static Message<byte[]> change(String type, String from, String to, String closeness, long version) {
        String since = closeness == null ? "null" : "\"" + SINCE + "\"";
        String close = closeness == null ? "null" : "\"" + closeness + "\"";
        return record("{\"type\":\"" + type + "\",\"fromUserId\":\"" + from + "\",\"fromUserName\":\"bob_l\","
                + "\"toUserId\":\"" + to + "\",\"closeness\":" + close + ",\"since\":" + since + ",\"version\":" + version
                + ",\"occurredAt\":\"2026-10-18T08:00:00Z\"}");
    }

    private static FriendshipChange applied(String type, Friendship after) {
        return new FriendshipChange(FriendshipChangeType.valueOf(type), after, null, OCCURRED);
    }

    @SafeVarargs
    private Mono<Void> consume(Message<byte[]>... records) {
        return consumer.friendshipHistory().apply(Flux.just(records));
    }

    @Test
    void friendshipHistory_shouldRecordAnAcceptanceAsFriends_whicheverSideActed() {
        FriendshipChange expected = applied("ACCEPTED", new Friendship(ALICE, BOB, true, Closeness.FRIEND, SINCE, 12));
        when(friendshipService.recordHistory(expected)).thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("ACCEPTED", BOB, ALICE, "FRIEND", 12))).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).recordHistory(expected);
    }

    @Test
    void friendshipHistory_shouldRecordTheSnapshotAndAClosenessChangeAsFriends() {
        when(friendshipService.recordHistory(any())).thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("SNAPSHOT", ALICE, BOB, "CLOSE", 1),
                change("CLOSENESS_CHANGED", ALICE, BOB, "INTIMATE", 2))).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).recordHistory(applied("SNAPSHOT", new Friendship(ALICE, BOB, true, Closeness.CLOSE, SINCE, 1)));
        verify(friendshipService).recordHistory(applied("CLOSENESS_CHANGED",
                new Friendship(ALICE, BOB, true, Closeness.INTIMATE, SINCE, 2)));
    }

    @Test
    void friendshipHistory_shouldRecordARemovalRejectionCancellationOrRequestAsNotFriends() {
        when(friendshipService.recordHistory(any())).thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("REMOVED", ALICE, BOB, null, 3), change("REJECTED", ALICE, BOB, null, 4),
                change("CANCELLED", ALICE, BOB, null, 5), change("REQUESTED", ALICE, BOB, null, 6))).expectComplete().verify(Duration.ofSeconds(10));
        String[] types = {"REMOVED", "REJECTED", "CANCELLED", "REQUESTED"};
        for (int i = 0; i < types.length; i++) {
            verify(friendshipService).recordHistory(applied(types[i], new Friendship(ALICE, BOB, false, null, null, i + 3)));
        }
    }

    @Test
    void friendshipHistory_shouldGoOn_whenARecordIsAlreadyInTheHistory() {
        when(friendshipService.recordHistory(applied("REMOVED", new Friendship(ALICE, BOB, false, null, null, 3))))
                .thenReturn(Mono.just(false));
        when(friendshipService.recordHistory(applied("ACCEPTED", new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 4))))
                .thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("REMOVED", ALICE, BOB, null, 3), change("ACCEPTED", ZOE, ALICE, "FRIEND", 4)))
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService, times(2)).recordHistory(any());
    }

    @Test
    void friendshipHistory_shouldDropMalformedRecords_andGoOn() {
        FriendshipChange valid = applied("ACCEPTED", new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 9));
        when(friendshipService.recordHistory(valid)).thenReturn(Mono.just(true));

        StepVerifier.create(consume(
                        record("not json"),
                        change("BEFRIENDED", ALICE, BOB, "FRIEND", 1),
                        change("ACCEPTED", ALICE, BOB, "BESTIE", 1),
                        change("ACCEPTED", ALICE, BOB, null, 1),
                        change("ACCEPTED", "not-a-uuid", BOB, "FRIEND", 1),
                        change("ACCEPTED", ALICE, ALICE, "FRIEND", 1),
                        record("{\"type\":\"ACCEPTED\",\"fromUserId\":\"" + ALICE + "\",\"toUserId\":\"" + BOB
                                + "\",\"closeness\":\"FRIEND\"}"),
                        record("{\"type\":\"ACCEPTED\",\"fromUserId\":\"" + ALICE + "\",\"toUserId\":\"" + BOB
                                + "\",\"closeness\":\"FRIEND\",\"since\":\"yesterday\",\"version\":1}"),
                        change("ACCEPTED", ZOE, ALICE, "FRIEND", 9)))
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).recordHistory(valid);
        verify(friendshipService, times(1)).recordHistory(any());
    }

    @Test
    void friendshipHistory_shouldRetryAFailure_thenGiveUpWithoutStoppingTheStream() {
        FriendshipChange failing = applied("ACCEPTED", new Friendship(ALICE, BOB, true, Closeness.FRIEND, SINCE, 1));
        FriendshipChange next = applied("ACCEPTED", new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 2));
        when(friendshipService.recordHistory(failing)).thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(friendshipService.recordHistory(next)).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> consume(change("ACCEPTED", ALICE, BOB, "FRIEND", 1),
                        change("ACCEPTED", ALICE, ZOE, "FRIEND", 2)))
                .thenAwait(Duration.ofMinutes(5))
                .verifyComplete();
        verify(friendshipService).recordHistory(next);
        // Counted once, after the retries; not a TechnicalException, so under the catch-all code.
        assertThat(technicalFailures(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }

    @Test
    void change_shouldLowercaseAnUppercaseUuid() {
        FriendshipChangedEventDTO dto = new FriendshipChangedEventDTO("REMOVED", BOB.toUpperCase(), ALICE, null, "CLOSE",
                null, 2L, OCCURRED);

        assertThat(FriendshipChangedConsumer.change(dto)).contains(new FriendshipChange(FriendshipChangeType.REMOVED,
                new Friendship(ALICE, BOB, false, null, null, 2), Closeness.CLOSE, OCCURRED));
    }

    @Test
    void change_shouldKeepThePreviousCloseness_andReadAnUnknownOneAsNone() {
        FriendshipChangedEventDTO upgrade = new FriendshipChangedEventDTO("CLOSENESS_CHANGED", ALICE, BOB, "INTIMATE",
                "CLOSE", SINCE, 3L, OCCURRED);
        FriendshipChangedEventDTO unknown = new FriendshipChangedEventDTO("CLOSENESS_CHANGED", ALICE, BOB, "INTIMATE",
                "BESTIE", SINCE, 3L, null);

        assertThat(FriendshipChangedConsumer.change(upgrade)).get()
                .satisfies(change -> assertThat(change.previousCloseness()).isEqualTo(Closeness.CLOSE));
        assertThat(FriendshipChangedConsumer.change(unknown)).get()
                .satisfies(change -> {
                    assertThat(change.previousCloseness()).isNull();
                    assertThat(change.at()).isNull();
                });
    }

    @Test
    void change_shouldBeEmpty_withoutATypeOrAVersion() {
        assertThat(FriendshipChangedConsumer.change(dto(null, BOB, 1L))).isEmpty();
        assertThat(FriendshipChangedConsumer.change(dto("REMOVED", BOB, null))).isEmpty();
        assertThat(FriendshipChangedConsumer.change(dto("REMOVED", null, 1L))).isEmpty();
        verify(friendshipService, never()).recordHistory(any());
    }

    private static FriendshipChangedEventDTO dto(String type, String to, Long version) {
        return new FriendshipChangedEventDTO(type, ALICE, to, null, null, null, version, null);
    }

    @Test
    void friendshipHistory_shouldCountAnUnreadableRecord() {
        StepVerifier.create(consume(record("not json"))).expectComplete().verify(Duration.ofSeconds(10));

        assertThat(technicalFailures(GlobalErrorHandler.UNEXPECTED_FAILURE_CODE)).isEqualTo(1.0);
    }
}
