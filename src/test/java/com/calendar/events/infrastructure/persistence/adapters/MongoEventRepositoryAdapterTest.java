package com.calendar.events.infrastructure.persistence.adapters;

import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.EventDetails;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoBox;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.TimeRange;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.mappers.EventPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveEventMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.query.Update;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoEventRepositoryAdapterTest {

    @Mock
    private ReactiveEventMongoRepository mongoRepository;

    @Mock
    private EventPersistenceMapper mapper;

    private MongoEventRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoEventRepositoryAdapter(mongoRepository, mapper);
    }

    @Test
    void findAll_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findAll()).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Flux<Event> result = adapter.findAll();

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findById_shouldReturnMappedEvent() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findById("1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Mono<Event> result = adapter.findById("1");

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void save_shouldMapAndSave() {
        Event domainInput = Event.builder().title("Title").build();
        EventEntity entityInput = new EventEntity();
        EventEntity entitySaved = new EventEntity();
        Event domainOutput = Event.builder().id("1").title("Title").build();

        when(mapper.toEntity(domainInput)).thenReturn(entityInput);
        when(mongoRepository.save(entityInput)).thenReturn(Mono.just(entitySaved));
        when(mapper.toDomain(entitySaved)).thenReturn(domainOutput);

        Mono<Event> result = adapter.save(domainInput);

        StepVerifier.create(result)
                .expectNext(domainOutput)
                .verifyComplete();
    }

    @Test
    void deleteByOrganizer_shouldReturnTheEventAsItWas() {
        EventEntity removed = new EventEntity();
        Event domain = Event.builder().id("1").build();
        when(mongoRepository.deleteByOrganizer("1", "org")).thenReturn(Mono.just(removed));
        when(mapper.toDomain(removed)).thenReturn(domain);

        StepVerifier.create(adapter.deleteByOrganizer("1", "org")).expectNext(domain).verifyComplete();
    }

    @Test
    void deleteByOrganizer_shouldBeEmpty_whenNothingMatched() {
        when(mongoRepository.deleteByOrganizer("1", "org")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteByOrganizer("1", "org")).verifyComplete();
    }

    @Test
    void deleteIfAlone_shouldReturnTheEventAsItWas() {
        EventEntity removed = new EventEntity();
        Event domain = Event.builder().id("1").build();
        when(mongoRepository.deleteIfOnlyAmong("1", "org", Set.of("org"))).thenReturn(Mono.just(removed));
        when(mapper.toDomain(removed)).thenReturn(domain);

        StepVerifier.create(adapter.deleteIfAlone("1", "org", Set.of())).expectNext(domain).verifyComplete();
    }

    @Test
    void deleteIfAlone_shouldBeEmpty_whenSomeoneElseTakesPart() {
        when(mongoRepository.deleteIfOnlyAmong("1", "org", Set.of("org"))).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteIfAlone("1", "org", List.of())).verifyComplete();
    }

    @Test
    void deleteIfAlone_shouldCountTheOrganizerAmongTheIdsToIgnore() {
        when(mongoRepository.deleteIfOnlyAmong("1", "org", Set.of("gone", "demo", "org"))).thenReturn(Mono.empty());

        StepVerifier.create(adapter.deleteIfAlone("1", "org", List.of("gone", "demo"))).verifyComplete();
        verify(mongoRepository).deleteIfOnlyAmong("1", "org", Set.of("gone", "demo", "org"));
    }

    @Test
    void findOrganizedBy_shouldMapEveryEvent() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().id("1").build();
        when(mongoRepository.findOrganizedBy("org")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findOrganizedBy("org")).expectNext(domain).verifyComplete();
    }

    @Test
    void handOverKeepingFormer_shouldReturnTheEvent_whenTheWriteMatched() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();
        when(mongoRepository.handOverKeepingFormer("1", "org", "lea")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.handOverKeepingFormer("1", "org", "lea")).expectNext(domain).verifyComplete();
    }

    @Test
    void handOverKeepingFormer_shouldBeEmpty_whenNothingMatched() {
        when(mongoRepository.handOverKeepingFormer("1", "org", "lea")).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.handOverKeepingFormer("1", "org", "lea")).verifyComplete();
        verify(mongoRepository, never()).findById("1");
    }

    @Test
    void leaveUpcoming_shouldPassTheInstantAndTheOneHourDefault() {
        Instant at = Instant.parse("2026-10-15T14:00:00Z");
        when(mongoRepository.leaveUpcoming("lea", at, Instant.parse("2026-10-15T13:00:00Z"))).thenReturn(Mono.just(2L));

        StepVerifier.create(adapter.leaveUpcoming("lea", at)).expectNext(2L).verifyComplete();
    }

    @Test
    void clearUpcomingResponses_shouldPassTheInstantAndTheOneHourDefault() {
        Instant at = Instant.parse("2026-10-15T14:00:00Z");
        when(mongoRepository.clearUpcomingResponses("lea", at, Instant.parse("2026-10-15T13:00:00Z")))
                .thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.clearUpcomingResponses("lea", at)).expectNext(1L).verifyComplete();
    }

    @Test
    void clearUpcomingResponses_shouldRejectAnIdUnusableAsAFieldName() {
        StepVerifier.create(adapter.clearUpcomingResponses("lea.$x", Instant.parse("2026-10-15T14:00:00Z")))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(mongoRepository, never()).clearUpcomingResponses(any(), any(), any());
    }

    @Test
    void handOver_shouldUpdateInPlaceThenReturnTheEvent() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();
        when(mongoRepository.handOver("1", "org", "lea")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.handOver("1", "org", "lea")).expectNext(domain).verifyComplete();
        verify(mongoRepository, never()).save(any());
    }

    @Test
    void handOver_shouldBeEmpty_whenNothingMatched() {
        when(mongoRepository.handOver("1", "org", "lea")).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.handOver("1", "org", "lea")).verifyComplete();
        verify(mongoRepository, never()).findById("1");
    }

    @Test
    void findByParticipantId_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findSubscribed("user1")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Flux<Event> result = adapter.findByParticipantId("user1");

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findOccupying_shouldLetEventsWithoutAnEndLastTheDefaultDuration() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();
        Instant from = Instant.parse("2026-10-16T08:00:00Z");
        Instant to = Instant.parse("2026-10-16T20:00:00Z");
        List<String> users = List.of("user1", "user2");

        when(mongoRepository.findOccupying(users, from, to, from.minus(Event.DEFAULT_DURATION), "viewer"))
                .thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findOccupying(users, from, to, "viewer"))
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findFeed_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();

        when(mongoRepository.findFeed("user1", List.of("friend"))).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        Flux<Event> result = adapter.findFeed("user1", List.of("friend"));

        StepVerifier.create(result)
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void addParticipant_shouldUpdateInPlaceThenReturnTheEvent() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();

        when(mongoRepository.addParticipant("1", "user123")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.addParticipant("1", "user123"))
                .expectNext(domain)
                .verifyComplete();

        // No save: a full rewrite would lose a concurrent subscription.
        verify(mongoRepository, never()).save(any());
    }

    @Test
    void addParticipant_shouldReturnNothing_whenTheEventDoesNotExist() {
        when(mongoRepository.addParticipant("missing", "user123")).thenReturn(Mono.just(0L));
        when(mongoRepository.findById("missing")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.addParticipant("missing", "user123"))
                .verifyComplete();
    }

    @Test
    void removeParticipant_shouldUpdateInPlaceThenReturnTheEvent() {
        EventEntity updated = new EventEntity();
        Event domain = Event.builder().id("1").build();

        when(mongoRepository.removeParticipant("1", "user123")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(updated));
        when(mapper.toDomain(updated)).thenReturn(domain);

        StepVerifier.create(adapter.removeParticipant("1", "user123"))
                .expectNext(domain)
                .verifyComplete();

        verify(mongoRepository, never()).save(any());
    }

    @Test
    void findOnMap_shouldPassTheBoxAndEndAnEventWithoutOneAfterAnHour() {
        Instant now = Instant.parse("2026-10-11T16:00:00Z");
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().id("1").build();
        when(mongoRepository.findOnMap("u", List.of("f"), 45.70, 4.77, 45.81, 4.92,
                now, now.minus(Event.DEFAULT_DURATION), Limit.of(200))).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findOnMap("u", List.of("f"), new GeoBox(45.70, 4.77, 45.81, 4.92), now, 200))
                .expectNext(domain)
                .verifyComplete();
    }

    @Test
    void findInvolving_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();
        when(mongoRepository.findInvolving("user1")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findInvolving("user1")).expectNext(domain).verifyComplete();
    }

    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    // An event without an end is over an hour after it starts.
    private static final Instant AN_HOUR_AGO = Instant.parse("2026-10-14T11:00:00Z");

    @Test
    void findUpcomingInvolving_shouldLetEventsWithoutAnEndLastTheDefaultDuration() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();
        when(mongoRepository.findUpcomingInvolving("bob", NOW, AN_HOUR_AGO, "alice")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findUpcomingInvolving("bob", "alice", NOW)).expectNext(domain).verifyComplete();
    }

    @Test
    void findUpcomingInvolvingBoth_shouldPassTheLimit_andLetEventsWithoutAnEndLastTheDefaultDuration() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();
        when(mongoRepository.findUpcomingInvolvingBoth("alice", "bob", NOW, AN_HOUR_AGO, Limit.of(5)))
                .thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findUpcomingInvolvingBoth("alice", "bob", NOW, 5)).expectNext(domain).verifyComplete();
    }

    @Test
    void findPastInvolvingBoth_shouldPassTheLimit_andLetEventsWithoutAnEndLastTheDefaultDuration() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().build();
        when(mongoRepository.findPastInvolvingBoth("alice", "bob", NOW, AN_HOUR_AGO, Limit.of(6)))
                .thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findPastInvolvingBoth("alice", "bob", NOW, 6)).expectNext(domain).verifyComplete();
    }

    @Test
    void countPastInvolvingBoth_shouldLetEventsWithoutAnEndLastTheDefaultDuration() {
        when(mongoRepository.countPastInvolvingBoth("alice", "bob", NOW, AN_HOUR_AGO)).thenReturn(Mono.just(4L));

        StepVerifier.create(adapter.countPastInvolvingBoth("alice", "bob", NOW)).expectNext(4L).verifyComplete();
    }

    @Test
    void setImage_shouldReadTheEventBack_whenTheOrganizersUpdateMatched() {
        EventEntity entity = new EventEntity();
        Event domain = Event.builder().id("1").image("events/1/cover-a.jpg").build();
        when(mongoRepository.setImage("1", "org", "events/1/cover-a.jpg")).thenReturn(Mono.just(1L));
        when(mongoRepository.findById("1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.setImage("1", "org", "events/1/cover-a.jpg")).expectNext(domain).verifyComplete();
    }

    @Test
    void setImage_shouldBeEmpty_whenNoEventOfThatOrganizerMatched() {
        when(mongoRepository.setImage("1", "someone-else", null)).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.setImage("1", "someone-else", null)).verifyComplete();

        verify(mongoRepository, never()).findById("1");
    }

    @Test
    void updateDetails_shouldWriteGeoJson_andReadTheEventBack() {
        EventDetails details = new EventDetails("T", "L", new GeoPoint(45.7, 4.8), "D", Visibility.PRIVATE);
        GeoJsonPoint point = new GeoJsonPoint(4.8, 45.7);
        Event domain = Event.builder().id("1").title("T").build();
        when(mapper.toGeoJson(details.coordinates())).thenReturn(point);
        when(mongoRepository.updateDetails("1", "org", "T", "L", point, "D", "PRIVATE")).thenReturn(Mono.just(1L));
        readBack(domain);

        StepVerifier.create(adapter.updateDetails("1", "org", details, false)).expectNext(domain).verifyComplete();
        verify(mongoRepository, never()).updateDetailsAndGroup(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateDetails_shouldMarkTheGroup_whenRenaming() {
        EventDetails details = new EventDetails("T", "L", null, null, null);
        Event domain = Event.builder().id("1").title("T").build();
        when(mongoRepository.updateDetailsAndGroup("1", "org", "T", "L", null, null, null)).thenReturn(Mono.just(1L));
        readBack(domain);

        StepVerifier.create(adapter.updateDetails("1", "org", details, true)).expectNext(domain).verifyComplete();
    }

    @Test
    void updateDetails_shouldBeEmpty_whenNoEventOfThatOrganizerMatched() {
        EventDetails details = new EventDetails("T", "L", null, null, null);
        when(mongoRepository.updateDetails("1", "someone-else", "T", "L", null, null, null)).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.updateDetails("1", "someone-else", details, false)).verifyComplete();
        verify(mongoRepository, never()).findById("1");
    }

    private static final PollSlot FRIDAY = new PollSlot("fri", Instant.parse("2026-10-17T17:00:00Z"), Instant.parse("2026-10-17T19:00:00Z"));
    private static final Instant THURSDAY_START = Instant.parse("2026-10-16T17:00:00Z");
    private static final Instant THURSDAY_END = Instant.parse("2026-10-16T19:00:00Z");

    private static Event withPoll(PollStatus status) {
        return Event.builder().id("1").poll(new Poll(status, List.of(FRIDAY), null, null)).build();
    }

    private void readBack(Event domain) {
        EventEntity entity = new EventEntity();
        when(mongoRepository.findById("1")).thenReturn(Mono.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);
    }

    @Test
    void castBallot_shouldStoreTheAnswersAsText_andReadTheEventBack() {
        Event after = withPoll(PollStatus.OPEN);
        when(mapper.toStoredAnswers(Map.of("fri", PollAnswer.YES))).thenReturn(Map.of("fri", "YES"));
        when(mongoRepository.castBallot("1", "lea", Map.of("fri", "YES"))).thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of("fri", PollAnswer.YES))).expectNext(after).verifyComplete();
    }

    @Test
    void castBallot_shouldWithdrawTheBallot_whenTheAnswersAreEmpty() {
        Event after = withPoll(PollStatus.OPEN);
        when(mongoRepository.withdrawBallot("1", "lea")).thenReturn(Mono.just(0L));
        readBack(after);

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of())).expectNext(after).verifyComplete();
        verify(mongoRepository, never()).castBallot(any(), any(), any());
    }

    @Test
    void castBallot_shouldCountAnUnchangedBallot_whileThePollIsStillOpen() {
        // Mongo reports modified documents: the same ballot twice modifies nothing, yet matched.
        Event after = withPoll(PollStatus.OPEN);
        when(mapper.toStoredAnswers(Map.of("fri", PollAnswer.YES))).thenReturn(Map.of("fri", "YES"));
        when(mongoRepository.castBallot("1", "lea", Map.of("fri", "YES"))).thenReturn(Mono.just(0L));
        readBack(after);

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of("fri", PollAnswer.YES))).expectNext(after).verifyComplete();
    }

    @Test
    void castBallot_shouldBeEmpty_whenNothingChangedAndThePollIsClosed() {
        when(mapper.toStoredAnswers(Map.of("fri", PollAnswer.YES))).thenReturn(Map.of("fri", "YES"));
        when(mongoRepository.castBallot("1", "lea", Map.of("fri", "YES"))).thenReturn(Mono.just(0L));
        readBack(withPoll(PollStatus.CLOSED));

        StepVerifier.create(adapter.castBallot("1", "lea", Map.of("fri", PollAnswer.YES))).verifyComplete();
    }

    @Test
    void castBallot_shouldRefuseAVoterIdThatWouldChangeTheFieldPath() {
        StepVerifier.create(adapter.castBallot("1", "a.b", Map.of()))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(adapter.castBallot("1", "$where", Map.of()))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void fixPollDate_shouldPassTheSlotAndYesVoters_andReadTheEventBack() {
        Event after = withPoll(PollStatus.CLOSED);
        when(mongoRepository.fixPollDate("1", "org", FRIDAY.start(), FRIDAY.end(), "fri", List.of("lea")))
                .thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.fixPollDate("1", "org", FRIDAY, List.of("lea"))).expectNext(after).verifyComplete();
    }

    @Test
    void fixPollDate_shouldBeEmpty_whenNoOpenPollMatched() {
        when(mongoRepository.fixPollDate("1", "org", FRIDAY.start(), FRIDAY.end(), "fri", List.of())).thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.fixPollDate("1", "org", FRIDAY, List.of())).verifyComplete();
        verify(mongoRepository, never()).findById("1");
    }

    @Test
    void reschedule_shouldMatchTheReadStart_keepTheReadEnd_andFlagTheAnnouncement() {
        Event read = Event.builder().id("1").organizerId("org").startDate(THURSDAY_START).endDate(THURSDAY_END).build();
        Event after = Event.builder().id("1").startDate(FRIDAY.start()).endDate(FRIDAY.end()).build();
        when(mongoRepository.reschedule("1", "org", THURSDAY_START, FRIDAY.start(), FRIDAY.end(), THURSDAY_END, false))
                .thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.reschedule(read, new TimeRange(FRIDAY.start(), FRIDAY.end()), true))
                .expectNext(after).verifyComplete();
    }

    @Test
    void reschedule_shouldClearTheAnnouncement_whenNobodyIsToBeTold() {
        Event read = Event.builder().id("1").organizerId("org").startDate(THURSDAY_START).build();
        when(mongoRepository.reschedule("1", "org", THURSDAY_START, FRIDAY.start(), FRIDAY.end(), null, null))
                .thenReturn(Mono.just(1L));
        readBack(read);

        StepVerifier.create(adapter.reschedule(read, new TimeRange(FRIDAY.start(), FRIDAY.end()), false))
                .expectNext(read).verifyComplete();
    }

    @Test
    void reschedule_shouldBeEmpty_whenTheDateChangedMeanwhile() {
        Event read = Event.builder().id("1").organizerId("org").startDate(THURSDAY_START).endDate(THURSDAY_END).build();
        when(mongoRepository.reschedule("1", "org", THURSDAY_START, FRIDAY.start(), FRIDAY.end(), THURSDAY_END, false))
                .thenReturn(Mono.just(0L));

        StepVerifier.create(adapter.reschedule(read, new TimeRange(FRIDAY.start(), FRIDAY.end()), true)).verifyComplete();
        verify(mongoRepository, never()).findById("1");
    }

    @Test
    void findOpenPollsInvolving_shouldReturnMappedEvents() {
        EventEntity entity = new EventEntity();
        Event domain = withPoll(PollStatus.OPEN);
        when(mongoRepository.findOpenPollsInvolving("lea")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findOpenPollsInvolving("lea")).expectNext(domain).verifyComplete();
    }

    @Test
    void findOpenPollsInvolving_shouldRefuseAUserIdThatWouldChangeTheFieldPath() {
        StepVerifier.create(adapter.findOpenPollsInvolving("a.b")).expectError(IllegalArgumentException.class).verify();
    }

    // --- Communities ------------------------------------------------------------------------------

    private static Event communityEvent(String organizer) {
        return Event.builder().id("1").organizerId(organizer).communityId("c1").participantIds(new ArrayList<>()).build();
    }

    @Test
    void findUpcomingInCommunity_shouldCountAnEventWithoutAnEndAsLastingTheDefaultDuration() {
        Instant now = Instant.parse("2026-10-15T10:00:00Z");
        EventEntity entity = new EventEntity();
        Event domain = communityEvent("bob");
        when(mongoRepository.findUpcomingInCommunity("c1", now, now.minus(Event.DEFAULT_DURATION), "alice")).thenReturn(Flux.just(entity));
        when(mapper.toDomain(entity)).thenReturn(domain);

        StepVerifier.create(adapter.findUpcomingInCommunity("c1", "alice", now)).expectNext(domain).verifyComplete();
    }

    @Test
    void respond_shouldJoinOnYes_andReadTheEventBack() {
        Event after = communityEvent("bob");
        when(mongoRepository.respondYes("1", "lea")).thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.respond("1", "lea", PollAnswer.YES)).expectNext(after).verifyComplete();
    }

    @Test
    void respond_shouldLeaveOnMaybe_whenTakingPart() {
        Event after = communityEvent("bob");
        when(mongoRepository.respondAndLeave("1", "lea", "MAYBE")).thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.respond("1", "lea", PollAnswer.MAYBE)).expectNext(after).verifyComplete();
        verify(mongoRepository, never()).respondFromOutside(any(), any(), any());
    }

    @Test
    void respond_shouldOnlyStoreTheAnswer_whenNotTakingPart() {
        Event after = communityEvent("bob");
        when(mongoRepository.respondAndLeave("1", "lea", "NO")).thenReturn(Mono.just(0L));
        when(mongoRepository.respondFromOutside("1", "lea", "NO")).thenReturn(Mono.just(1L));
        readBack(after);

        StepVerifier.create(adapter.respond("1", "lea", PollAnswer.NO)).expectNext(after).verifyComplete();
    }

    @Test
    void respond_shouldBeEmpty_forTheOrganizerSteppingOut() {
        when(mongoRepository.respondAndLeave("1", "bob", "NO")).thenReturn(Mono.just(0L));
        when(mongoRepository.respondFromOutside("1", "bob", "NO")).thenReturn(Mono.just(0L));
        readBack(communityEvent("bob"));

        StepVerifier.create(adapter.respond("1", "bob", PollAnswer.NO)).verifyComplete();
    }

    @Test
    void respond_shouldBeEmpty_forAnEventOutsideAnyCommunity() {
        when(mongoRepository.respondYes("1", "lea")).thenReturn(Mono.just(0L));
        readBack(Event.builder().id("1").organizerId("bob").build());

        StepVerifier.create(adapter.respond("1", "lea", PollAnswer.YES)).verifyComplete();
    }

    @Test
    void respond_shouldRefuseAnIdUnusableAsAFieldName() {
        StepVerifier.create(adapter.respond("1", "lea.$x", PollAnswer.NO))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(mongoRepository, never()).respondAndLeave(any(), any(), any());
    }

    // --- Hidden events, birthdays and surprises ---------------------------------------------------

    private Event mapped(EventEntity entity) {
        Event domain = Event.builder().id(entity.getId()).build();
        when(mapper.toDomain(entity)).thenReturn(domain);
        return domain;
    }

    @Test
    void findShownTo_shouldAskForTheEventAsTheViewerMaySeeIt() {
        EventEntity entity = EventEntity.builder().id("1").build();
        Event domain = mapped(entity);
        when(mongoRepository.findShownTo("1", "lea")).thenReturn(Mono.just(entity));

        StepVerifier.create(adapter.findShownTo("1", "lea")).expectNext(domain).verifyComplete();
    }

    @Test
    void upsertBirthday_shouldSaveUnderTheUsersKnownBirthdayId() {
        Event birthday = Event.builder().organizerId("lea").build();
        EventEntity entity = new EventEntity();
        when(mapper.toEntity(birthday)).thenReturn(entity);
        when(mongoRepository.save(entity)).thenReturn(Mono.just(entity));
        Event saved = Event.builder().build();
        when(mapper.toDomain(entity)).thenReturn(saved);

        StepVerifier.create(adapter.upsertBirthday(birthday)).expectNext(saved).verifyComplete();
        org.assertj.core.api.Assertions.assertThat(entity.getId()).isEqualTo("birthday-lea");
    }

    @Test
    void findBirthdayOf_shouldMapTheUsersBirthday() {
        EventEntity entity = EventEntity.builder().id("birthday-lea").build();
        Event domain = mapped(entity);
        when(mongoRepository.findBirthdayOf("lea")).thenReturn(Mono.just(entity));

        StepVerifier.create(adapter.findBirthdayOf("lea")).expectNext(domain).verifyComplete();
    }

    @Test
    void findBirthdaysOf_shouldMapTheirBirthdays() {
        EventEntity entity = EventEntity.builder().id("birthday-lea").build();
        Event domain = mapped(entity);
        when(mongoRepository.findBirthdaysOf(java.util.List.of("lea", "bob"))).thenReturn(Flux.just(entity));

        StepVerifier.create(adapter.findBirthdaysOf(java.util.List.of("lea", "bob"))).expectNext(domain).verifyComplete();
    }

    @Test
    void findAllBirthdays_shouldMapEveryBirthday() {
        EventEntity entity = EventEntity.builder().id("birthday-lea").build();
        Event domain = mapped(entity);
        when(mongoRepository.findAllBirthdays()).thenReturn(Flux.just(entity));

        StepVerifier.create(adapter.findAllBirthdays()).expectNext(domain).verifyComplete();
    }

    @Test
    void deleteBirthdayOf_shouldCountWhatWent() {
        when(mongoRepository.deleteBirthdayOf("lea")).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.deleteBirthdayOf("lea")).expectNext(1L).verifyComplete();
    }

    @Test
    void insertSurprise_shouldInsertTheSurprise() {
        Event surprise = Event.builder().build();
        EventEntity entity = EventEntity.builder().id("s1").build();
        when(mapper.toEntity(surprise)).thenReturn(entity);
        when(mongoRepository.insert(entity)).thenReturn(Mono.just(entity));
        Event domain = mapped(entity);

        StepVerifier.create(adapter.insertSurprise(surprise)).expectNext(domain).verifyComplete();
    }

    @Test
    void insertSurprise_shouldAnswerEmpty_whenTheBirthdayHasASurpriseAlready() {
        Event surprise = Event.builder().build();
        EventEntity entity = new EventEntity();
        when(mapper.toEntity(surprise)).thenReturn(entity);
        when(mongoRepository.insert(entity))
                .thenReturn(Mono.error(new org.springframework.dao.DuplicateKeyException("occurrenceKey")));

        StepVerifier.create(adapter.insertSurprise(surprise)).verifyComplete();
    }

    @Test
    void insertSurprise_shouldPassAnyOtherFailureOn() {
        Event surprise = Event.builder().build();
        EventEntity entity = new EventEntity();
        when(mapper.toEntity(surprise)).thenReturn(entity);
        when(mongoRepository.insert(entity))
                .thenReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.insertSurprise(surprise))
                .expectError(org.springframework.dao.DataAccessResourceFailureException.class).verify();
    }

    @Test
    void findSurprisesFor_shouldAskOnBehalfOfTheViewer() {
        EventEntity entity = EventEntity.builder().id("s1").build();
        Event domain = mapped(entity);
        when(mongoRepository.findSurprisesFor(java.util.List.of("lea:2027"), "bob")).thenReturn(Flux.just(entity));

        StepVerifier.create(adapter.findSurprisesFor(java.util.List.of("lea:2027"), "bob")).expectNext(domain).verifyComplete();
    }

    @Test
    void findSurpriseByOccurrence_shouldMapTheSurprise() {
        EventEntity entity = EventEntity.builder().id("s1").build();
        Event domain = mapped(entity);
        when(mongoRepository.findSurpriseByOccurrence("lea:2027")).thenReturn(Mono.just(entity));

        StepVerifier.create(adapter.findSurpriseByOccurrence("lea:2027")).expectNext(domain).verifyComplete();
    }

    @Test
    void findSurpriseStartingBetween_shouldTakeTheFirstOnly() {
        java.time.Instant from = java.time.Instant.parse("2027-01-11T18:00:00Z");
        java.time.Instant to = java.time.Instant.parse("2027-05-11T18:00:00Z");
        EventEntity entity = EventEntity.builder().id("s1").build();
        Event domain = mapped(entity);
        when(mongoRepository.findSurprisesStartingBetween("lea", from, to, org.springframework.data.domain.Limit.of(1)))
                .thenReturn(Flux.just(entity));

        StepVerifier.create(adapter.findSurpriseStartingBetween("lea", from, to)).expectNext(domain).verifyComplete();
    }

    @Test
    void findSurprisesHonoring_shouldMapThem() {
        EventEntity entity = EventEntity.builder().id("s1").build();
        Event domain = mapped(entity);
        when(mongoRepository.findSurprisesHonoring("lea")).thenReturn(Flux.just(entity));

        StepVerifier.create(adapter.findSurprisesHonoring("lea")).expectNext(domain).verifyComplete();
    }
}
