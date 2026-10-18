package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.ports.FriendshipDirectory;
import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.domain.services.SurpriseService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs {@link FriendshipStateConsumer} through the real service and adapters on MongoDB, the surprises
 * mocked to count the releases; opt-in via {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class FriendshipStateConsumerIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    // ALICE sorts before BOB.
    private static final String ALICE = "1b4e28ba-2fa1-41d2-883f-0016d3cca427";
    private static final String BOB = "89a380b8-f66a-44d0-b9d9-616241080430";
    private static final String KEY = ALICE + ":" + BOB;
    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");

    @Autowired
    private FriendshipService friendshipService;
    @Autowired
    private FriendshipDirectory directory;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ReactiveMongoTemplate mongoTemplate;
    @MockitoBean
    private SurpriseService surpriseService;

    private FriendshipStateConsumer consumer;

    @BeforeEach
    void setUp() {
        mongoTemplate.remove(new Query(), "friendships").then().block();
        mongoTemplate.remove(new Query(), "deleted_users").then().block();
        when(surpriseService.releaseAfterFriendshipEnded(anyString(), anyString())).thenReturn(Mono.just(0L));
        consumer = new FriendshipStateConsumer(friendshipService, objectMapper);
    }

    private static Message<byte[]> value(String closeness, long version) {
        String json = "{\"a\":\"" + ALICE + "\",\"b\":\"" + BOB + "\",\"friends\":true,\"closeness\":\"" + closeness
                + "\",\"since\":\"" + SINCE + "\",\"version\":" + version + "}";
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8))
                .setHeader(KafkaHeaders.RECEIVED_KEY, KEY.getBytes(StandardCharsets.UTF_8)).build();
    }

    @SuppressWarnings("unchecked")
    private static Message<byte[]> tombstone() {
        return (Message<byte[]>) (Message<?>) MessageBuilder.withPayload(KafkaNull.INSTANCE)
                .setHeader(KafkaHeaders.RECEIVED_KEY, KEY.getBytes(StandardCharsets.UTF_8)).build();
    }

    @SafeVarargs
    private void consume(Message<byte[]>... records) {
        consumer.friendshipState().apply(Flux.just(records)).block();
    }

    private Friendship stored() {
        return directory.pairOf(ALICE, BOB).block();
    }

    @Test
    void friendshipState_shouldEndTheFriendshipAndReleaseOnce_evenWhenRedelivered() {
        consume(value("CLOSE", 1), tombstone(), tombstone(), value("CLOSE", 1), tombstone());

        assertThat(stored()).isEqualTo(new Friendship(ALICE, BOB, false, null, null, 1));
        verify(surpriseService, times(1)).releaseAfterFriendshipEnded(ALICE, BOB);
    }

    @Test
    void friendshipState_shouldKeepANewerProjection_whenReplayedFromTheStart() {
        consume(value("INTIMATE", 5));

        // A new group re-reads what compaction has not cleaned yet: an older value, its tombstone, the latest.
        consume(value("FRIEND", 3), tombstone(), value("INTIMATE", 5));

        assertThat(stored()).isEqualTo(new Friendship(ALICE, BOB, true, Closeness.INTIMATE, SINCE, 5));
        verify(surpriseService, never()).releaseAfterFriendshipEnded(anyString(), anyString());
    }

    @Test
    void friendshipState_shouldStoreFriendsAgain_afterATombstone() {
        consume(value("FRIEND", 1), tombstone(), value("CLOSE", 2));

        assertThat(stored()).isEqualTo(new Friendship(ALICE, BOB, true, Closeness.CLOSE, SINCE, 2));
        verify(surpriseService, times(1)).releaseAfterFriendshipEnded(ALICE, BOB);
    }

    @Test
    void friendshipState_shouldEndAPairStoredBeforeTheStateTopic() {
        directory.record(new Friendship(ALICE, BOB, true, Closeness.FRIEND, SINCE, 4)).block();
        mongoTemplate.getCollection("friendships")
                .flatMap(collection -> Mono.from(collection.updateOne(new Document("_id", KEY),
                        new Document("$unset", new Document("streamedVersion", "")))))
                .block();

        consume(tombstone());

        assertThat(stored().friends()).isFalse();
        verify(surpriseService, times(1)).releaseAfterFriendshipEnded(ALICE, BOB);
    }
}
