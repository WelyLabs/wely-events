package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.domain.models.FriendshipNoticeType;
import com.calendar.events.infrastructure.persistence.entities.FriendshipNoticeEntity;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipNoticePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipNoticeMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoFriendshipNoticeOutboxAdapterTest {

    private static final Instant NOW = Instant.parse("2026-10-18T06:00:00Z");
    private static final FriendshipNotice NOTICE = FriendshipNotice.anniversary("me", "lea", "Léa", Closeness.INTIMATE,
            1, Instant.parse("2025-10-18T09:00:00Z"), LocalDate.of(2026, 10, 18));

    @Mock
    private ReactiveFriendshipNoticeMongoRepository mongoRepository;

    private final FriendshipNoticePersistenceMapper mapper = Mappers.getMapper(FriendshipNoticePersistenceMapper.class);
    private MongoFriendshipNoticeOutboxAdapter outbox;

    @BeforeEach
    void setUp() {
        outbox = new MongoFriendshipNoticeOutboxAdapter(mongoRepository, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void record_shouldInsertTheNoticeUnderItsKey_dated() {
        when(mongoRepository.insert(argThat((FriendshipNoticeEntity entity) -> NOTICE.key().equals(entity.getId())
                && NOW.equals(entity.getRecordedAt()) && !entity.isPublished())))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(outbox.record(NOTICE)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldSayFalse_whenTheKeyIsTaken() {
        when(mongoRepository.insert(any(FriendshipNoticeEntity.class))).thenReturn(Mono.error(new DuplicateKeyException("dup")));

        StepVerifier.create(outbox.record(NOTICE)).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldPassAnyOtherFailureOn() {
        when(mongoRepository.insert(any(FriendshipNoticeEntity.class)))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(outbox.record(NOTICE)).expectError(DataAccessResourceFailureException.class).verify(Duration.ofSeconds(10));
    }

    @Test
    void recordedFor_shouldAskByTypeName() {
        when(mongoRepository.findRecordedFor("me", "FRIENDSHIP_ANNIVERSARY", NOW))
                .thenReturn(Flux.just(mapper.toEntity(NOTICE, NOW)));

        StepVerifier.create(outbox.recordedFor("me", FriendshipNoticeType.FRIENDSHIP_ANNIVERSARY, NOW))
                .expectNext(NOTICE).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void findPending_shouldReadUpToTheLimit() {
        when(mongoRepository.findPending(Limit.of(10))).thenReturn(Flux.just(mapper.toEntity(NOTICE, NOW)));

        StepVerifier.create(outbox.findPending(10)).expectNext(NOTICE).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldMarkByKey() {
        when(mongoRepository.markPublished(NOTICE.key())).thenReturn(Mono.just(1L));

        StepVerifier.create(outbox.markPublished(NOTICE.key())).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void forget_shouldDropTheUnsentNoticesConcerningTheUser() {
        when(mongoRepository.deletePendingConcerning("lea")).thenReturn(Mono.just(2L));

        StepVerifier.create(outbox.forget("lea")).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void countPending_shouldCountWhatIsLeftToSend_onTheServer() {
        when(mongoRepository.countPending()).thenReturn(Mono.just(7L));

        StepVerifier.create(outbox.countPending()).expectNext(7L).expectComplete().verify(Duration.ofSeconds(10));
    }
}
