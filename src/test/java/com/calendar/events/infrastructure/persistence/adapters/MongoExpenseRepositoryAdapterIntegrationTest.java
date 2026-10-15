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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link MongoExpenseRepositoryAdapter} against a real MongoDB, opt-in via {@code -Dintegration.tests=true}. */
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
                SplitMode.EQUAL, List.of(new Share("alice", 0), new Share("bob", 0)), spentAt, null, null, null)).block();
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
                ExpenseKind.EXPENSE, SplitMode.EQUAL, original.shares(), original.spentAt(), null, null, original.version());
        Expense secondEdit = new Expense(original.id(), "ev-1", "Courses du soir", 1500, "EUR", "alice", "alice",
                ExpenseKind.EXPENSE, SplitMode.EQUAL, original.shares(), original.spentAt(), null, null, original.version());

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

    @Test
    @DisplayName("a receipt is set only on that event's expense, and bumps the version")
    void setReceipt_shouldFilterOnTheEventAndBumpTheVersion() {
        Expense original = stored("ev-1", "Courses", Instant.parse("2026-10-13T10:00:00Z"));

        StepVerifier.create(adapter.setReceipt("ev-other", original.id(), "events/ev-1/receipts/r.jpg", "alice"))
                .verifyComplete();
        StepVerifier.create(adapter.setReceipt("ev-1", original.id(), "events/ev-1/receipts/r.jpg", "alice"))
                .assertNext(updated -> {
                    assertThat(updated.receiptKey()).isEqualTo("events/ev-1/receipts/r.jpg");
                    assertThat(updated.receiptAddedBy()).isEqualTo("alice");
                    assertThat(updated.version()).isEqualTo(original.version() + 1);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("an edit opened before the receipt changed is refused rather than writing the old key back")
    void setReceipt_shouldMakeAnOlderEditConflict() {
        Expense original = stored("ev-1", "Courses", Instant.parse("2026-10-13T10:00:00Z"));
        adapter.setReceipt("ev-1", original.id(), "events/ev-1/receipts/r.jpg", "alice").block();
        Expense staleEdit = new Expense(original.id(), "ev-1", "Courses bio", 1200, "EUR", "alice", "alice",
                ExpenseKind.EXPENSE, SplitMode.EQUAL, original.shares(), original.spentAt(), null, null, original.version());

        StepVerifier.create(adapter.save(staleEdit))
                .expectErrorMatches(error -> error instanceof EventException exception
                        && exception.getErrorCode() == EventErrorCode.EXPENSE_CONFLICT)
                .verify();
    }

    private Expense recordedBy(String eventId, String author) {
        return adapter.save(new Expense(null, eventId, "Courses", 1000, "EUR", author, author, ExpenseKind.EXPENSE,
                SplitMode.EQUAL, List.of(new Share(author, 0), new Share("bob", 0)),
                Instant.parse("2026-10-13T10:00:00Z"), null, null, null)).block();
    }

    @Test
    void findWithReceiptRecordedBy_shouldFindOnlyTheUsersExpensesWithAReceipt_acrossEvents() {
        Expense first = recordedBy("ev-1", "gone");
        Expense second = recordedBy("ev-2", "gone");
        recordedBy("ev-1", "gone");
        Expense someoneElses = recordedBy("ev-1", "alice");
        adapter.setReceipt("ev-1", first.id(), "events/ev-1/receipts/a.jpg", "gone").block();
        adapter.setReceipt("ev-2", second.id(), "events/ev-2/receipts/b.jpg", "gone").block();
        adapter.setReceipt("ev-1", someoneElses.id(), "events/ev-1/receipts/c.jpg", "alice").block();

        StepVerifier.create(adapter.findWithReceiptRecordedBy("gone").map(Expense::receiptKey).collectList())
                .assertNext(keys -> assertThat(keys)
                        .containsExactlyInAnyOrder("events/ev-1/receipts/a.jpg", "events/ev-2/receipts/b.jpg"))
                .verifyComplete();
    }

    @Test
    void findWithReceiptRecordedBy_shouldSkipAReceiptSomeoneElseAttached_andKeepALegacyOne() {
        Expense own = recordedBy("ev-1", "gone");
        Expense organizers = recordedBy("ev-1", "gone");
        Expense legacy = recordedBy("ev-1", "gone");
        adapter.setReceipt("ev-1", own.id(), "events/ev-1/receipts/own.jpg", "gone").block();
        adapter.setReceipt("ev-1", organizers.id(), "events/ev-1/receipts/organizer.jpg", "olivia").block();
        // As stored before the attacher was recorded: no receiptAddedBy field at all.
        mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(legacy.id())),
                new Update().set("receiptKey", "events/ev-1/receipts/legacy.jpg").unset("receiptAddedBy"),
                "expenses").block();

        StepVerifier.create(adapter.findWithReceiptRecordedBy("gone").map(Expense::receiptKey).collectList())
                .assertNext(keys -> assertThat(keys)
                        .containsExactlyInAnyOrder("events/ev-1/receipts/own.jpg", "events/ev-1/receipts/legacy.jpg"))
                .verifyComplete();
    }

    @Test
    @DisplayName("a receipt is detached only while it is still the one read, and the version moves")
    void clearReceipt_shouldUnsetTheKeyAndBumpTheVersion_onlyWhileUnchanged() {
        Expense expense = recordedBy("ev-1", "gone");
        Expense withReceipt = adapter.setReceipt("ev-1", expense.id(), "events/ev-1/receipts/a.jpg", "gone").block();

        StepVerifier.create(adapter.clearReceipt("ev-1", expense.id(), "events/ev-1/receipts/other.jpg"))
                .expectNext(false).verifyComplete();
        StepVerifier.create(adapter.clearReceipt("ev-other", expense.id(), "events/ev-1/receipts/a.jpg"))
                .expectNext(false).verifyComplete();
        StepVerifier.create(adapter.clearReceipt("ev-1", expense.id(), "events/ev-1/receipts/a.jpg"))
                .expectNext(true).verifyComplete();

        StepVerifier.create(adapter.findByIdInEvent("ev-1", expense.id()))
                .assertNext(cleared -> {
                    assertThat(cleared.receiptKey()).isNull();
                    assertThat(cleared.receiptAddedBy()).isNull();
                    assertThat(cleared.version()).isEqualTo(withReceipt.version() + 1);
                })
                .verifyComplete();
        StepVerifier.create(adapter.findWithReceiptRecordedBy("gone")).verifyComplete();
        StepVerifier.create(adapter.clearReceipt("ev-1", expense.id(), "events/ev-1/receipts/a.jpg"))
                .expectNext(false).verifyComplete();
    }

    @Test
    void setReceipt_shouldClearTheKey_whenGivenNull() {
        Expense original = stored("ev-1", "Courses", Instant.parse("2026-10-13T10:00:00Z"));
        adapter.setReceipt("ev-1", original.id(), "events/ev-1/receipts/r.jpg", "alice").block();

        StepVerifier.create(adapter.setReceipt("ev-1", original.id(), null, null))
                .assertNext(updated -> {
                    assertThat(updated.receiptKey()).isNull();
                    assertThat(updated.receiptAddedBy()).isNull();
                })
                .verifyComplete();
    }
}
