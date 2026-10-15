package com.calendar.events.domain.services;

import com.calendar.events.domain.models.DeletedUserMarker;
import com.calendar.events.domain.models.ErasureReport;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Expense;
import com.calendar.events.domain.models.ExpenseKind;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.Share;
import com.calendar.events.domain.models.SplitMode;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Runs {@link AccountErasureService} through the real adapters on MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class AccountErasureServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String GONE = "8f0c1d2e-3b4a-4c5d-9e6f-7a8b9c0d1e2f";
    private static final Instant DELETED_AT = Instant.parse("2026-10-15T14:00:00Z");
    private static final Instant PAST_START = DELETED_AT.minusSeconds(7 * 86_400);
    private static final Instant FUTURE_START = DELETED_AT.plusSeconds(7 * 86_400);

    @Autowired
    private AccountErasureService service;
    @Autowired
    private EventRepository events;
    @Autowired
    private ExpenseRepository expenses;
    @Autowired
    private DeletedUserMarkers markers;
    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @MockitoBean
    private MediaStore mediaStore;

    @BeforeEach
    void clearCollections() {
        // Documents, not the collections: dropping takes the indexes with them.
        for (String collection : List.of("events", "expenses", "event_deletions", "deleted_users")) {
            mongoTemplate.remove(new Query(), collection).then().block();
        }
        when(mediaStore.delete(anyString())).thenReturn(Mono.empty());
    }

    private String event(String title, String organizer, Instant start, List<String> participants) {
        return events.save(Event.builder().title(title).organizerId(organizer).startDate(start)
                .endDate(start.plusSeconds(7200)).location("Lyon").visibility(Visibility.FRIENDS)
                .participantIds(new ArrayList<>(participants)).build()).block().getId();
    }

    private String poll(String title, String organizer, PollStatus status, Map<String, Map<String, PollAnswer>> ballots) {
        PollSlot slot = new PollSlot("s1", FUTURE_START, FUTURE_START.plusSeconds(7200));
        return events.save(Event.builder().title(title).organizerId(organizer).startDate(slot.start())
                .endDate(slot.end()).location("Lyon").participantIds(new ArrayList<>(List.of(organizer)))
                .poll(new Poll(status, List.of(slot), ballots, status == PollStatus.CLOSED ? "s1" : null))
                .build()).block().getId();
    }

    private Expense expense(String eventId, String author, String... people) {
        return expenses.save(new Expense(null, eventId, "Courses", 1000, "EUR", author, author, ExpenseKind.EXPENSE,
                SplitMode.EQUAL, Arrays.stream(people).map(id -> new Share(id, 0)).toList(),
                DELETED_AT, null, null, null)).block();
    }

    private Event read(String id) {
        return events.findById(id).block();
    }

    private EventEntity stored(String id) {
        return mongoTemplate.findById(id, EventEntity.class).block();
    }

    /** As if the relay had sent every group: what the erasure flags is then visible. */
    private void allGroupsSent() {
        mongoTemplate.updateMulti(new Query(), new Update().set("groupSynced", true), EventEntity.class).block();
    }

    @Test
    void erase_shouldLeavePastEventsWithThePseudonymAndUpcomingEventsWithoutIt() {
        String past = event("past", "alice", PAST_START, List.of("alice", GONE));
        String upcoming = event("upcoming", "alice", FUTURE_START, List.of("alice", GONE));

        service.erase(GONE, DELETED_AT).block();

        assertThat(read(past).getParticipantIds()).containsExactly("alice", GONE);
        assertThat(read(upcoming).getParticipantIds()).containsExactly("alice");
    }

    @Test
    void erase_shouldHandOverInJoinOrderAndDeleteASoloEventWithItsExpensesAndTombstone() {
        String upcoming = event("upcoming", GONE, FUTURE_START, List.of(GONE, "lea", "hugo"));
        String past = event("past", GONE, PAST_START, List.of(GONE, "hugo", "lea"));
        String solo = event("solo", GONE, FUTURE_START, List.of(GONE));
        expense(solo, GONE, GONE);

        ErasureReport report = service.erase(GONE, DELETED_AT).block();

        assertThat(report.eventsHandedOver()).isEqualTo(2);
        assertThat(report.eventsDeleted()).isEqualTo(1);
        assertThat(read(upcoming).getOrganizerId()).isEqualTo("lea");
        assertThat(read(upcoming).getParticipantIds()).containsExactly("lea", "hugo");
        assertThat(read(past).getOrganizerId()).isEqualTo("hugo");
        assertThat(read(past).getParticipantIds()).containsExactly(GONE, "hugo", "lea");
        assertThat(read(solo)).isNull();
        assertThat(expenses.findByEvent(solo).collectList().block()).isEmpty();
        assertThat(mongoTemplate.findById(solo, Map.class, "event_deletions").block()).isNotNull();
    }

    @Test
    void erase_shouldFlagEveryChangedGroupForTheRelay_andOnlyThose() {
        String handedOver = event("handed over", GONE, FUTURE_START, List.of(GONE, "lea"));
        String left = event("left", "alice", FUTURE_START, List.of("alice", GONE));
        String pastJoined = event("past joined", "alice", PAST_START, List.of("alice", GONE));
        String unrelated = event("unrelated", "alice", FUTURE_START, List.of("alice", "lea"));
        allGroupsSent();

        service.erase(GONE, DELETED_AT).block();

        assertThat(stored(handedOver).getGroupSynced()).isFalse();
        assertThat(stored(left).getGroupSynced()).isFalse();
        assertThat(stored(pastJoined).getGroupSynced()).isTrue();
        assertThat(stored(unrelated).getGroupSynced()).isTrue();
    }

    @Test
    void erase_shouldChangeNoVersion_whenReplayed() {
        String handedOver = event("handed over", GONE, FUTURE_START, List.of(GONE, "lea"));
        String pastHandedOver = event("past handed over", GONE, PAST_START, List.of(GONE, "lea"));
        String left = event("left", "alice", FUTURE_START, List.of("alice", GONE));
        service.erase(GONE, DELETED_AT).block();
        Long[] versions = {stored(handedOver).getGroupVersion(), stored(pastHandedOver).getGroupVersion(),
                stored(left).getGroupVersion()};

        ErasureReport replay = service.erase(GONE, DELETED_AT.plusSeconds(86_400)).block();

        assertThat(replay).isEqualTo(ErasureReport.NONE);
        assertThat(new Long[]{stored(handedOver).getGroupVersion(), stored(pastHandedOver).getGroupVersion(),
                stored(left).getGroupVersion()}).containsExactly(versions);
    }

    @Test
    void erase_shouldKeepExpensesAndClosedBallots_andWithdrawOpenBallots() {
        String open = poll("open", "alice", PollStatus.OPEN, Map.of(GONE, Map.of("s1", PollAnswer.YES),
                "lea", Map.of("s1", PollAnswer.NO)));
        String closed = poll("closed", "alice", PollStatus.CLOSED, Map.of(GONE, Map.of("s1", PollAnswer.YES)));
        String upcoming = event("upcoming", "alice", FUTURE_START, List.of("alice", GONE));
        Expense shared = expense(upcoming, "alice", "alice", GONE);

        ErasureReport report = service.erase(GONE, DELETED_AT).block();

        assertThat(report.ballotsWithdrawn()).isEqualTo(1);
        assertThat(read(open).getPoll().ballots()).containsOnlyKeys("lea");
        assertThat(read(closed).getPoll().ballots()).containsKey(GONE);
        assertThat(expenses.findByIdInEvent(upcoming, shared.id()).block().people()).contains(GONE);
    }

    @Test
    void erase_shouldDropTheReceiptsTheUserRecorded_andNoneOther() {
        String upcoming = event("upcoming", "alice", FUTURE_START, List.of("alice", GONE));
        Expense theirs = expense(upcoming, GONE, GONE, "alice");
        Expense attachedByAlice = expense(upcoming, GONE, GONE, "alice");
        Expense aliceS = expense(upcoming, "alice", "alice", GONE);
        expenses.setReceipt(upcoming, theirs.id(), "events/" + upcoming + "/receipts/theirs.jpg", GONE).block();
        expenses.setReceipt(upcoming, attachedByAlice.id(), "events/" + upcoming + "/receipts/by-alice.jpg", "alice").block();
        expenses.setReceipt(upcoming, aliceS.id(), "events/" + upcoming + "/receipts/alice.jpg", "alice").block();

        ErasureReport report = service.erase(GONE, DELETED_AT).block();

        assertThat(report.receiptsDropped()).isEqualTo(1);
        assertThat(expenses.findByIdInEvent(upcoming, theirs.id()).block().receiptKey()).isNull();
        assertThat(expenses.findByIdInEvent(upcoming, attachedByAlice.id()).block().receiptKey()).isNotNull();
        assertThat(expenses.findByIdInEvent(upcoming, aliceS.id()).block().receiptKey()).isNotNull();
        verify(mediaStore).delete("events/" + upcoming + "/receipts/theirs.jpg");
    }

    @Test
    void erase_shouldKeepAReceiptTheOrganizerAttachedAfterTheErasure_whenReplayed() {
        String upcoming = event("upcoming", "alice", FUTURE_START, List.of("alice", GONE));
        Expense theirs = expense(upcoming, GONE, GONE, "alice");
        expenses.setReceipt(upcoming, theirs.id(), "events/" + upcoming + "/receipts/theirs.jpg", GONE).block();
        service.erase(GONE, DELETED_AT).block();
        expenses.setReceipt(upcoming, theirs.id(), "events/" + upcoming + "/receipts/alice.jpg", "alice").block();

        ErasureReport replay = service.erase(GONE, DELETED_AT).block();

        assertThat(replay.receiptsDropped()).isZero();
        assertThat(expenses.findByIdInEvent(upcoming, theirs.id()).block().receiptKey())
                .isEqualTo("events/" + upcoming + "/receipts/alice.jpg");
        verify(mediaStore, never()).delete("events/" + upcoming + "/receipts/alice.jpg");
    }

    @Test
    void erase_shouldRecordTheMarkerWithTheDeletionTime_thenTheErasureTime() {
        service.erase(GONE, DELETED_AT).block();

        DeletedUserMarker marker = markers.markDeleted(GONE, DELETED_AT.plusSeconds(60)).block();
        assertThat(marker.deletedAt()).isEqualTo(DELETED_AT);
        assertThat(marker.erasedAt()).isNotNull();
    }
}
