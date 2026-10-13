package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs {@link MongoExpenseRepositoryAdapter} against a real MongoDB: that a lookup naming the wrong
 * event finds nothing, and that a save carrying a stale version is refused, are claims about the
 * queries and the {@code @Version} field that only MongoDB can settle.
 *
 * <p>Opt-in: CI sets {@code CI=true}, locally pass {@code -Dintegration.tests=true}.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class MongoExpenseRepositoryAdapterIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    @Autowired
    private MongoExpenseRepositoryAdapter adapter;

    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void clearExpenses() {
        // Documents, not the collection: dropping takes the indexes with it.
        mongoTemplate.remove(new Query(), "expenses").then().block();
    }

    private Expense stored(String eventId, String title, Instant spentAt) {
        return adapter.save(new Expense(null, eventId, title, 1000, "EUR", "alice", "alice", ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share("alice", 0), new Share("bob", 0)), spentAt, null)).block();
    }

    @Test
    @DisplayName("an expense id from another event finds nothing, and deletes nothing")
    void findByIdInEvent_shouldIgnoreAnExpenseOfAnotherEvent() {
        Expense theirs = stored("ev-other", "Leur dépense", Instant.parse("2026-10-13T10:00:00Z"));

        StepVerifier.create(adapter.findByIdInEvent("ev-mine", theirs.id())).verifyComplete();
        StepVerifier.create(adapter.delete("ev-mine", theirs.id())).verifyComplete();
        StepVerifier.create(adapter.findByIdInEvent("ev-other", theirs.id())).expectNextCount(1).verifyComplete();
    }

    @Test
    void findByEvent_shouldListOnlyThatEvent_newestFirst() {
        stored("ev-1", "Vendredi", Instant.parse("2026-10-09T10:00:00Z"));
        stored("ev-1", "Samedi", Instant.parse("2026-10-10T10:00:00Z"));
        stored("ev-2", "Ailleurs", Instant.parse("2026-10-11T10:00:00Z"));

        StepVerifier.create(adapter.findByEvent("ev-1").map(Expense::title).collectList())
                .assertNext(titles -> assertThat(titles).containsExactly("Samedi", "Vendredi"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a save carrying the version read before someone else's save is refused")
    void save_shouldRefuseAStaleVersion() {
        Expense original = stored("ev-1", "Courses", Instant.parse("2026-10-13T10:00:00Z"));
        Expense firstEdit = new Expense(original.id(), "ev-1", "Courses bio", 1200, "EUR", "alice", "alice",
                ExpenseKind.EXPENSE, SplitMode.EQUAL, original.shares(), original.spentAt(), original.version());
        Expense secondEdit = new Expense(original.id(), "ev-1", "Courses du soir", 1500, "EUR", "alice", "alice",
                ExpenseKind.EXPENSE, SplitMode.EQUAL, original.shares(), original.spentAt(), original.version());

        StepVerifier.create(adapter.save(firstEdit)).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.save(secondEdit))
                .expectErrorMatches(error -> error instanceof EventException exception
                        && exception.getErrorCode() == EventErrorCode.EXPENSE_CONFLICT)
                .verify();
    }

    @Test
    void deleteByEvent_shouldRemoveOnlyThatEventsExpenses() {
        stored("ev-1", "Courses", Instant.parse("2026-10-13T10:00:00Z"));
        stored("ev-2", "Essence", Instant.parse("2026-10-13T10:00:00Z"));

        StepVerifier.create(adapter.deleteByEvent("ev-1")).verifyComplete();

        StepVerifier.create(adapter.findByEvent("ev-1")).verifyComplete();
        StepVerifier.create(adapter.findByEvent("ev-2")).expectNextCount(1).verifyComplete();
    }
}
