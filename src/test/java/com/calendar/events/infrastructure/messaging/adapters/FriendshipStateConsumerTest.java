package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.infrastructure.messaging.models.FriendshipStateDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipStateConsumerTest {

    // ALICE sorts before BOB.
    private static final String ALICE = "1b4e28ba-2fa1-41d2-883f-0016d3cca427";
    private static final String BOB = "89a380b8-f66a-44d0-b9d9-616241080430";
    private static final String ZOE = "c56a4180-65aa-42ec-a945-5fd21dec0538";
    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");

    @Mock
    private FriendshipService friendshipService;

    private FriendshipStateConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new FriendshipStateConsumer(friendshipService, JsonMapper.builder().build());
    }

    private static Message<byte[]> value(String key, String json) {
        MessageBuilder<byte[]> builder = MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            builder.setHeader(KafkaHeaders.RECEIVED_KEY, key.getBytes(StandardCharsets.UTF_8));
        }
        return builder.build();
    }

    private static Message<byte[]> state(String a, String b, String closeness, long version) {
        String close = closeness == null ? "null" : "\"" + closeness + "\"";
        return value(a + ":" + b, "{\"a\":\"" + a + "\",\"b\":\"" + b + "\",\"friends\":true,\"closeness\":" + close
                + ",\"since\":\"" + SINCE + "\",\"version\":" + version + "}");
    }

    // Spring Cloud Function passes a tombstone on with a KafkaNull payload, whatever the declared type.
    @SuppressWarnings("unchecked")
    private static Message<byte[]> tombstone(Object key) {
        MessageBuilder<KafkaNull> builder = MessageBuilder.withPayload(KafkaNull.INSTANCE);
        if (key != null) {
            builder.setHeader(KafkaHeaders.RECEIVED_KEY, key);
        }
        return (Message<byte[]>) (Message<?>) builder.build();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @SafeVarargs
    private Mono<Void> consume(Message<byte[]>... records) {
        return consumer.friendshipState().apply(Flux.just(records));
    }

    @Test
    void friendshipState_shouldApplyAValueAsFriends() {
        Friendship expected = new Friendship(ALICE, BOB, true, Closeness.CLOSE, SINCE, 12);
        when(friendshipService.applyState(expected)).thenReturn(Mono.just(true));

        StepVerifier.create(consume(state(ALICE, BOB, "CLOSE", 12))).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).applyState(expected);
    }

    @Test
    void friendshipState_shouldApplyAValueWithoutAKey() {
        when(friendshipService.applyState(any())).thenReturn(Mono.just(true));

        StepVerifier.create(consume(value(null, "{\"a\":\"" + BOB + "\",\"b\":\"" + ALICE
                + "\",\"friends\":true,\"closeness\":\"FRIEND\",\"since\":\"" + SINCE + "\",\"version\":3}"))).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).applyState(new Friendship(ALICE, BOB, true, Closeness.FRIEND, SINCE, 3));
    }

    @Test
    void friendshipState_shouldEndTheFriendshipTheTombstoneKeyNames() {
        when(friendshipService.applyTombstone(ALICE, BOB)).thenReturn(Mono.just(true));

        StepVerifier.create(consume(tombstone(bytes(ALICE + ":" + BOB)))).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).applyTombstone(ALICE, BOB);
        verify(friendshipService, never()).applyState(any());
    }

    @Test
    void friendshipState_shouldReadATombstoneKeyGivenAsAString() {
        when(friendshipService.applyTombstone(ALICE, BOB)).thenReturn(Mono.just(false));

        StepVerifier.create(consume(tombstone(ALICE.toUpperCase() + ":" + BOB))).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).applyTombstone(ALICE, BOB);
    }

    @Test
    void friendshipState_shouldDropATombstoneWithoutAUsableKey_andGoOn() {
        when(friendshipService.applyTombstone(ALICE, ZOE)).thenReturn(Mono.just(true));

        StepVerifier.create(consume(tombstone(null), tombstone(bytes("not-a-key")), tombstone(bytes(ALICE + ":" + ALICE)),
                tombstone(bytes(ALICE + ":" + BOB + ":" + ZOE)), tombstone(42), tombstone(bytes(ALICE + ":" + ZOE))))
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService, times(1)).applyTombstone(anyString(), anyString());
    }

    @Test
    void friendshipState_shouldDropMalformedValues_andGoOn() {
        Friendship valid = new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 9);
        when(friendshipService.applyState(valid)).thenReturn(Mono.just(true));
        String pair = ALICE + ":" + BOB;

        StepVerifier.create(consume(
                        value(pair, "not json"),
                        state(ALICE, BOB, "BESTIE", 1),
                        state(ALICE, BOB, null, 1),
                        state("not-a-uuid", BOB, "FRIEND", 1),
                        state(ALICE, ALICE, "FRIEND", 1),
                        value(pair, "{\"a\":\"" + ALICE + "\",\"b\":\"" + BOB + "\",\"friends\":true,\"closeness\":\"FRIEND\"}"),
                        value(pair, "{\"a\":\"" + ALICE + "\",\"b\":\"" + BOB + "\",\"closeness\":\"FRIEND\",\"version\":1}"),
                        value(ALICE + ":" + ZOE, "{\"a\":\"" + ALICE + "\",\"b\":\"" + BOB
                                + "\",\"friends\":true,\"closeness\":\"FRIEND\",\"version\":1}"),
                        state(ZOE, ALICE, "FRIEND", 9)))
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService).applyState(valid);
        verify(friendshipService, times(1)).applyState(any());
    }

    @Test
    void friendshipState_shouldGoOn_whenAStaleVersionIsIgnored() {
        when(friendshipService.applyState(new Friendship(ALICE, BOB, true, Closeness.FRIEND, SINCE, 3)))
                .thenReturn(Mono.just(false));
        when(friendshipService.applyTombstone(ALICE, BOB)).thenReturn(Mono.just(false));
        when(friendshipService.applyState(new Friendship(ALICE, BOB, true, Closeness.CLOSE, SINCE, 5)))
                .thenReturn(Mono.just(false));

        StepVerifier.create(consume(state(ALICE, BOB, "FRIEND", 3), tombstone(bytes(ALICE + ":" + BOB)),
                state(ALICE, BOB, "CLOSE", 5))).expectComplete().verify(Duration.ofSeconds(10));
        verify(friendshipService, times(2)).applyState(any());
        verify(friendshipService).applyTombstone(ALICE, BOB);
    }

    @Test
    void friendshipState_shouldRetryAFailure_thenGiveUpWithoutStoppingTheStream() {
        when(friendshipService.applyTombstone(ALICE, BOB)).thenReturn(Mono.error(new IllegalStateException("mongo down")));
        Friendship next = new Friendship(ALICE, ZOE, true, Closeness.FRIEND, SINCE, 2);
        when(friendshipService.applyState(next)).thenReturn(Mono.just(true));

        StepVerifier.withVirtualTime(() -> consume(tombstone(bytes(ALICE + ":" + BOB)), state(ALICE, ZOE, "FRIEND", 2)))
                .thenAwait(Duration.ofMinutes(5))
                .verifyComplete();
        verify(friendshipService, times(FriendshipStateConsumer.MAX_RETRIES + 1)).applyTombstone(ALICE, BOB);
        verify(friendshipService).applyState(next);
    }

    @Test
    void state_shouldLowercaseAnUppercaseUuid_andSortThePair() {
        FriendshipStateDTO dto = new FriendshipStateDTO(BOB.toUpperCase(), ALICE, true, "INTIMATE", SINCE, 2L);

        assertThat(FriendshipStateConsumer.state(dto))
                .contains(new Friendship(ALICE, BOB, true, Closeness.INTIMATE, SINCE, 2));
    }

    @Test
    void state_shouldBeEmpty_withoutFriendsOrAVersion() {
        assertThat(FriendshipStateConsumer.state(new FriendshipStateDTO(ALICE, BOB, null, "FRIEND", SINCE, 1L))).isEmpty();
        assertThat(FriendshipStateConsumer.state(new FriendshipStateDTO(ALICE, BOB, true, "FRIEND", SINCE, null))).isEmpty();
        assertThat(FriendshipStateConsumer.state(new FriendshipStateDTO(null, BOB, true, "FRIEND", SINCE, 1L))).isEmpty();
    }

    @Test
    void pairOfKey_shouldReadBothIds_fromBytesOrAString() {
        assertThat(FriendshipStateConsumer.pairOfKey(bytes(ALICE + ":" + BOB)))
                .contains(new FriendshipStateConsumer.KeyPair(ALICE, BOB));
        assertThat(FriendshipStateConsumer.pairOfKey(BOB + ":" + ALICE.toUpperCase()))
                .contains(new FriendshipStateConsumer.KeyPair(BOB, ALICE));
        assertThat(FriendshipStateConsumer.pairOfKey(null)).isEmpty();
        assertThat(FriendshipStateConsumer.pairOfKey(ALICE)).isEmpty();
        assertThat(FriendshipStateConsumer.pairOfKey(ALICE + ":")).isEmpty();
    }
}
