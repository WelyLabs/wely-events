package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.FriendshipChange;
import com.calendar.events.domain.models.FriendshipChangeType;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.infrastructure.messaging.models.FriendshipChangedEventDTO;
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
        consumer = new FriendshipChangedConsumer(friendshipService, JsonMapper.builder().build());
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
        return consumer.friendships().apply(Flux.just(records));
    }

    @Test
    void friendships_shouldRecordAnAcceptanceAsFriends_whicheverSideActed() {
        FriendshipChange expected = applied("ACCEPTED", new Friendship(ALICE, BOB, true, Closeness.FRIEND, SINCE, 12));
        when(friendshipService.record(expected)).thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("ACCEPTED", BOB, ALICE, "FRIEND", 12))).verifyComplete();
        verify(friendshipService).record(expected);
    }

    @Test
    void friendships_shouldRecordTheSnapshotAndAClosenessChangeAsFriends() {
        when(friendshipService.record(any())).thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("SNAPSHOT", ALICE, BOB, "CLOSE", 1),
                change("CLOSENESS_CHANGED", ALICE, BOB, "INTIMATE", 2))).verifyComplete();
        verify(friendshipService).record(applied("SNAPSHOT", new Friendship(ALICE, BOB, true, Closeness.CLOSE, SINCE, 1)));
        verify(friendshipService).record(applied("CLOSENESS_CHANGED",
                new Friendship(ALICE, BOB, true, Closeness.INTIMATE, SINCE, 2)));
    }

    @Test
    void friendships_shouldRecordARemovalRejectionCancellationOrRequestAsATombstone() {
        when(friendshipService.record(any())).thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("REMOVED", ALICE, BOB, null, 3), change("REJECTED", ALICE, BOB, null, 4),
                change("CANCELLED", ALICE, BOB, null, 5), change("REQUESTED", ALICE, BOB, null, 6))).verifyComplete();
        String[] types = {"REMOVED", "REJECTED", "CANCELLED", "REQUESTED"};
        for (int i = 0; i < types.length; i++) {
            verify(friendshipService).record(applied(types[i], new Friendship(ALICE, BOB, false, null, null, i + 3)));
        }
    }

    @Test
    void friendships_shouldGoOn_whenAStaleVersionIsIgnored() {
        when(friendshipService.record(applied("REMOVED", new Friendship(ALICE, BOB, false, null, null, 3))))
                .thenReturn(Mono.just(false));
        when(friendshipService.record(applied("ACCEPTED", new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 4))))
                .thenReturn(Mono.just(true));

        StepVerifier.create(consume(change("REMOVED", ALICE, BOB, null, 3), change("ACCEPTED", ZOE, ALICE, "FRIEND", 4)))
                .verifyComplete();
        verify(friendshipService, times(2)).record(any());
    }

    @Test
    void friendships_shouldDropMalformedRecords_andGoOn() {
        FriendshipChange valid = applied("ACCEPTED", new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 9));
        when(friendshipService.record(valid)).thenReturn(Mono.just(true));

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
                .verifyComplete();
        verify(friendshipService).record(valid);
        verify(friendshipService, times(1)).record(any());
    }

    @Test
    void friendships_shouldRetryAFailure_thenGiveUpWithoutStoppingTheStream() {
        FriendshipChange failing = applied("ACCEPTED", new Friendship(ALICE, BOB, true, Closeness.FRIEND, SINCE, 1));
        FriendshipChange next = applied("ACCEPTED", new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 2));
        when(friendshipService.record(failing)).thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(friendshipService.record(next)).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> consume(change("ACCEPTED", ALICE, BOB, "FRIEND", 1),
                        change("ACCEPTED", ALICE, ZOE, "FRIEND", 2)))
                .thenAwait(Duration.ofMinutes(5))
                .verifyComplete();
        verify(friendshipService).record(next);
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
        verify(friendshipService, never()).record(any());
    }

    private static FriendshipChangedEventDTO dto(String type, String to, Long version) {
        return new FriendshipChangedEventDTO(type, ALICE, to, null, null, null, version, null);
    }
}
