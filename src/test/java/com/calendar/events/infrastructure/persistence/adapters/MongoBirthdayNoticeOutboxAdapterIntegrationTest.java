package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.infrastructure.persistence.entities.BirthdayNoticeEntity;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoBirthdayNoticeOutboxAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoBirthdayNoticeOutboxAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private MongoBirthdayNoticeOutboxAdapter outbox;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @Autowired
    private MongoMappingContext mappingContext;

    @BeforeEach
    void clearNotices() {
        mongoTemplate.remove(new Query(), "birthday_notices").then().block();
    }

    private static BirthdayNotice friendBirthday(String recipient, String honoree) {
        return new BirthdayNotice(BirthdayNoticeType.FRIEND_BIRTHDAY, recipient, honoree, "Léa",
                LocalDate.parse("2027-03-12"), 0, Closeness.CLOSE, null, null, null);
    }

    @Test
    void record_shouldRecordANoticeOnce_evenSentAlready() {
        BirthdayNotice notice = friendBirthday("bob", "lea");

        StepVerifier.create(outbox.record(notice)).expectNext(true).verifyComplete();
        StepVerifier.create(outbox.record(notice)).expectNext(false).verifyComplete();
        outbox.markPublished(notice.key()).block();
        StepVerifier.create(outbox.record(notice)).expectNext(false).verifyComplete();
    }

    @Test
    void findPending_shouldListOnlyWhatIsNotSentYet() {
        BirthdayNotice sent = friendBirthday("bob", "lea");
        BirthdayNotice pending = friendBirthday("zoe", "lea");
        outbox.record(sent).block();
        outbox.record(pending).block();
        outbox.markPublished(sent.key()).block();

        StepVerifier.create(outbox.findPending(10)).expectNext(pending).verifyComplete();
    }

    @Test
    void forget_shouldDropTheUnsentNoticesForOrAboutTheUser_keepingTheRest() {
        outbox.record(friendBirthday("bob", "lea")).block();
        outbox.record(friendBirthday("lea", "max")).block();
        outbox.record(friendBirthday("bob", "max")).block();

        outbox.forget("lea").block();

        assertThat(outbox.findPending(10).map(BirthdayNotice::key).collectList().block())
                .containsExactly("max:bob:2027:FRIEND_BIRTHDAY");
    }

    @Test
    void birthdayNotices_shouldExpireThroughATtlIndexOnRecordedAt() {
        // The test profile turns auto-index-creation off: the indexes the entity declares are made here.
        IndexResolver.create(mappingContext).resolveIndexFor(BirthdayNoticeEntity.class)
                .forEach(index -> mongoTemplate.indexOps(BirthdayNoticeEntity.class).createIndex(index).block());

        assertThat(mongoTemplate.getCollection("birthday_notices")
                .flatMapMany(collection -> collection.listIndexes())
                .filter(index -> index.containsKey("expireAfterSeconds"))
                .map(index -> ((Number) index.get("expireAfterSeconds")).longValue())
                .collectList().block())
                .containsExactly(400L * 24 * 3600);
    }
}
