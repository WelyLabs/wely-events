package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.infrastructure.persistence.entities.ReunionSnoozeEntity;
import com.calendar.events.infrastructure.persistence.mappers.ReunionSnoozePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveReunionSnoozeMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoReunionSnoozesAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @Mock
    private ReactiveReunionSnoozeMongoRepository mongoRepository;

    private final ReunionSnoozePersistenceMapper mapper = Mappers.getMapper(ReunionSnoozePersistenceMapper.class);
    private MongoReunionSnoozesAdapter snoozes;

    @BeforeEach
    void setUp() {
        snoozes = new MongoReunionSnoozesAdapter(mongoRepository, mapper);
    }

    @Test
    void snooze_shouldSaveThePairsSnooze() {
        when(mongoRepository.save(argThat((ReunionSnoozeEntity entity) -> "me:hugo".equals(entity.getId())
                && NOW.equals(entity.getUntil())))).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(snoozes.snooze("me", "hugo", NOW)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void snoozedBy_shouldCollectTheSnoozedFriends() {
        when(mongoRepository.findActiveBy("me", NOW)).thenReturn(Flux.just(mapper.toEntity("me", "hugo", NOW.plusSeconds(60)),
                mapper.toEntity("me", "lea", NOW.plusSeconds(60))));

        StepVerifier.create(snoozes.snoozedBy("me", NOW)).expectNext(Set.of("hugo", "lea")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void snoozedBy_shouldPassAReadFailureOn() {
        when(mongoRepository.findActiveBy("me", NOW)).thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(snoozes.snoozedBy("me", NOW)).expectError(DataAccessResourceFailureException.class).verify(Duration.ofSeconds(10));
    }

    @Test
    void forget_shouldDeleteEverySnoozeInvolvingTheUser() {
        when(mongoRepository.deleteInvolving("me")).thenReturn(Mono.just(2L));

        StepVerifier.create(snoozes.forget("me")).expectComplete().verify(Duration.ofSeconds(10));
    }
}
