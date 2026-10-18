package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.models.ReunionCandidate;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendshipDirectory;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Duration;
import java.time.Instant;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs {@link ReunionService} through the real adapters on MongoDB, opt-in via {@code -Dintegration.tests=true}. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
@EnabledIf("containersRequested")
class ReunionServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:6.0");

    static boolean containersRequested() {
        return System.getenv("CI") != null || Boolean.getBoolean("integration.tests");
    }

    private static final String ME = "me";
    // The clock bean is the system's: every date is relative to now.
    private static final Instant NOW = Instant.now();
    private static final Instant A_YEAR_AGO = NOW.minus(Duration.ofDays(365));

    @Autowired
    private ReunionService service;
    @Autowired
    private FriendshipDirectory friendships;
    @Autowired
    private EventRepository events;
    @Autowired
    private ReactiveMongoTemplate mongoTemplate;

    @BeforeEach
    void seed() {
        for (String collection : List.of("events", "friendships", "reunion_snoozes")) {
            mongoTemplate.remove(new Query(), collection).then().block();
        }
    }

    private void friends(String friendId, Closeness closeness, Instant since) {
        friendships.record(new Friendship(ME, friendId, true, closeness, since, 1)).block();
    }

    private void outing(String title, String friendId, Instant start) {
        events.save(Event.builder().title(title).organizerId(ME).startDate(start).endDate(start.plusSeconds(7200))
                .location("Lyon").participantIds(new ArrayList<>(List.of(ME, friendId))).build()).block();
    }

    @Test
    void candidatesFor_shouldFindTheCloseFriendsDriftedFrom_mostDueFirst_named_andForgetASnoozedOne() {
        friends("hugo", Closeness.CLOSE, A_YEAR_AGO);
        friends("lea", Closeness.INTIMATE, A_YEAR_AGO);
        friends("seen", Closeness.INTIMATE, A_YEAR_AGO);
        friends("planned", Closeness.CLOSE, A_YEAR_AGO);
        friends("plain", Closeness.FRIEND, A_YEAR_AGO);
        friends("new", Closeness.INTIMATE, NOW.minus(Duration.ofDays(30)));
        outing("with hugo", "hugo", NOW.minus(Duration.ofDays(150)));
        outing("with seen", "seen", NOW.minus(Duration.ofDays(20)));
        outing("long ago with planned", "planned", NOW.minus(Duration.ofDays(200)));
        outing("next week with planned", "planned", NOW.plus(Duration.ofDays(7)));
        events.upsertBirthday(BirthdayService.birthdayEvent("hugo", "Hugo", MonthDay.of(1, 5))).block();

        assertThat(service.candidatesFor(ME).collectList().block())
                .extracting(ReunionCandidate::friendId, ReunionCandidate::name, ReunionCandidate::lastSharedTitle)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("lea", DisplayNames.FALLBACK, null),
                        org.assertj.core.groups.Tuple.tuple("hugo", "Hugo", "with hugo"));

        service.dismiss(ME, "lea").block();

        assertThat(service.reunionsFor(ME).map(ReunionCandidate::friendId).collectList().block()).containsExactly("hugo");
    }
}
