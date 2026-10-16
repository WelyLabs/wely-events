package com.calendar.events.domain.services;

import com.calendar.events.domain.models.DeletedUser;
import com.calendar.events.domain.models.DeletedUsersPage;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.ReconciliationReport;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.DeletedUsersSource;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.infrastructure.persistence.entities.DeletedUserEntity;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** Runs {@link AccountReconciliationService} through the real erasure on MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class AccountReconciliationServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String GONE = "8f0c1d2e-3b4a-4c5d-9e6f-7a8b9c0d1e2f";

    @Autowired
    private AccountReconciliationService service;
    @Autowired
    private AccountErasureService erasure;
    @Autowired
    private EventRepository events;
    @Autowired
    private DeletedUserMarkers markers;
    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @MockitoBean
    private DeletedUsersSource source;
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

    private void listed(Instant deletedAt) {
        when(source.page(isNull(), anyInt()))
                .thenReturn(Mono.just(new DeletedUsersPage(List.of(new DeletedUser(GONE, deletedAt)), null)));
    }

    private String upcoming(String organizer, List<String> participants) {
        Instant start = Instant.now().plus(Duration.ofDays(7));
        return events.save(Event.builder().title("upcoming").organizerId(organizer).startDate(start)
                .endDate(start.plusSeconds(7200)).location("Lyon").visibility(Visibility.FRIENDS)
                .participantIds(new ArrayList<>(participants)).build()).block().getId();
    }

    private Event read(String id) {
        return events.findById(id).block();
    }

    @Test
    @DisplayName("a USER_DELETED this service never applied is repaired from wely-users' tombstone")
    void reconcile_shouldEraseATombstone_whenItsMarkerIsMissing() {
        Instant deletedAt = Instant.now().minus(Duration.ofDays(30));
        String organized = upcoming(GONE, List.of(GONE, "lea"));
        listed(deletedAt);

        assertThat(service.reconcile().block()).isEqualTo(new ReconciliationReport(1, 1, 0));

        assertThat(read(organized).getOrganizerId()).isEqualTo("lea");
        assertThat(read(organized).getParticipantIds()).containsExactly("lea");
        DeletedUserEntity marker = mongoTemplate.findById(GONE, DeletedUserEntity.class).block();
        assertThat(marker.getDeletedAt()).isEqualTo(deletedAt.truncatedTo(ChronoUnit.MILLIS));
        assertThat(marker.getErasedAt()).isNotNull();
    }

    @Test
    @DisplayName("a participation that slipped in after the erasure is swept while the deletion is recent")
    void reconcile_shouldEraseAgain_whenTheTombstoneIsUnderTheRecentWindow() {
        Instant deletedAt = Instant.now().minus(Duration.ofHours(1));
        erasure.erase(GONE, deletedAt).block();
        String joinedLate = upcoming("lea", List.of("lea", GONE));
        listed(deletedAt);

        assertThat(service.reconcile().block()).isEqualTo(new ReconciliationReport(1, 1, 0));

        assertThat(read(joinedLate).getParticipantIds()).containsExactly("lea");
    }

    @Test
    @DisplayName("an old tombstone already erased here is left alone: no erasure runs")
    void reconcile_shouldSkipAnErasedTombstone_whenItIsOlderThanTheRecentWindow() {
        Instant deletedAt = Instant.now().minus(Duration.ofDays(30));
        erasure.erase(GONE, deletedAt).block();
        String joinedLate = upcoming("lea", List.of("lea", GONE));
        listed(deletedAt);

        assertThat(service.reconcile().block()).isEqualTo(new ReconciliationReport(1, 0, 0));

        // The participation left in place proves no erasure ran.
        assertThat(read(joinedLate).getParticipantIds()).containsExactly("lea", GONE);
        assertThat(markers.isDeleted(GONE).block()).isTrue();
    }
}
