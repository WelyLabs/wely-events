package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.FriendshipNoticeType;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.infrastructure.persistence.entities.FriendshipNoticeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoFriendshipNoticeOutboxAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoFriendshipNoticeOutboxAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Instant SINCE = Instant.parse("2025-10-18T09:00:00Z");

    @Autowired
    private MongoFriendshipNoticeOutboxAdapter outbox;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mappingContext;

    @BeforeEach
    void clearNotices() {
        mongoTemplate.remove(new Query(), "friendship_notices").then().block();
    }

    private static FriendshipNotice nudge(String recipient, String friend, LocalDate day) {
        return FriendshipNotice.reunionNudge(recipient, new ReunionCandidate(friend, "Hugo", Closeness.CLOSE, SINCE,
                Instant.parse("2026-05-02T18:00:00Z"), "e1", "Ciné", 5), day);
    }

    private static FriendshipNotice anniversary(String recipient, String friend) {
        return FriendshipNotice.anniversary(recipient, friend, "Léa", Closeness.INTIMATE, 1, SINCE, DAY);
    }

    @Test
    void record_shouldRecordANoticeOnce_evenSentAlready_andOneNudgeADayWhoeverItIsAbout() {
        FriendshipNotice notice = nudge("me", "hugo", DAY);

        StepVerifier.create(outbox.record(notice)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(outbox.record(nudge("me", "lea", DAY))).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
        outbox.markPublished(notice.key()).block();
        StepVerifier.create(outbox.record(notice)).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
        StepVerifier.create(outbox.record(anniversary("me", "lea"))).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldKeepEveryField() {
        FriendshipNotice notice = nudge("me", "hugo", DAY);
        outbox.record(notice).block();

        StepVerifier.create(outbox.findPending(10)).expectNext(notice).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findPending_shouldListOnlyWhatIsNotSentYet() {
        FriendshipNotice sent = anniversary("me", "lea");
        FriendshipNotice pending = anniversary("lea", "me");
        outbox.record(sent).block();
        outbox.record(pending).block();
        outbox.markPublished(sent.key()).block();

        StepVerifier.create(outbox.findPending(10)).expectNext(pending).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void recordedFor_shouldReadTheRecipientsNoticesOfThatType_sentOrNot_sinceTheDate() {
        FriendshipNotice sent = nudge("me", "hugo", DAY.minusDays(3));
        outbox.record(sent).block();
        outbox.markPublished(sent.key()).block();
        outbox.record(nudge("other", "hugo", DAY)).block();
        outbox.record(anniversary("me", "lea")).block();

        assertThat(outbox.recordedFor("me", FriendshipNoticeType.REUNION_NUDGE, Instant.now().minus(Duration.ofDays(30)))
                .collectList().block()).containsExactly(sent);
        assertThat(outbox.recordedFor("me", FriendshipNoticeType.REUNION_NUDGE, Instant.now().plusSeconds(60))
                .collectList().block()).isEmpty();
    }

    @Test
    void forget_shouldDropTheUnsentNoticesForOrAboutTheUser_keepingTheRest() {
        outbox.record(anniversary("bob", "lea")).block();
        outbox.record(anniversary("lea", "max")).block();
        outbox.record(anniversary("bob", "max")).block();

        outbox.forget("lea").block();

        assertThat(outbox.findPending(10).map(FriendshipNotice::key).collectList().block())
                .containsExactly("FRIENDSHIP_ANNIVERSARY:bob:max:1");
    }

    @Test
    void friendshipNotices_shouldExpireThroughATtlIndexOnRecordedAt() {
        // The test profile turns auto-index-creation off: the indexes the entity declares are made here.
        IndexResolver.create(mappingContext).resolveIndexFor(FriendshipNoticeEntity.class)
                .forEach(index -> mongoTemplate.indexOps(FriendshipNoticeEntity.class).createIndex(index).block());

        assertThat(mongoTemplate.getCollection("friendship_notices")
                .flatMapMany(collection -> collection.listIndexes())
                .filter(index -> index.containsKey("expireAfterSeconds"))
                .map(index -> ((Number) index.get("expireAfterSeconds")).longValue())
                .collectList().block())
                .containsExactly(400L * 24 * 3600);
    }
}
