package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.models.BirthdayNoticeType;
import com.calendar.events.domain.models.Closeness;
import com.calendar.events.infrastructure.persistence.entities.BirthdayNoticeEntity;
import com.calendar.events.infrastructure.persistence.mappers.BirthdayNoticePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveBirthdayNoticeMongoRepository;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoBirthdayNoticeOutboxAdapterTest {

    private static final Instant NOW = Instant.parse("2027-02-19T07:00:00Z");
    private static final BirthdayNotice NOTICE = new BirthdayNotice(BirthdayNoticeType.FRIEND_BIRTHDAY, "bob", "lea",
            "Léa", LocalDate.parse("2027-02-19"), 0, Closeness.CLOSE, null, null, null);

    @Mock
    private ReactiveBirthdayNoticeMongoRepository mongoRepository;

    private MongoBirthdayNoticeOutboxAdapter outbox;

    @BeforeEach
    void setUp() {
        outbox = new MongoBirthdayNoticeOutboxAdapter(mongoRepository,
                Mappers.getMapper(BirthdayNoticePersistenceMapper.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void record_shouldInsertTheNoticeUnderItsKey_unsent() {
        when(mongoRepository.insert(argThat((BirthdayNoticeEntity entity) -> "lea:bob:2027:FRIEND_BIRTHDAY".equals(entity.getId())
                && !entity.isPublished() && NOW.equals(entity.getRecordedAt()) && "CLOSE".equals(entity.getCloseness()))))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(outbox.record(NOTICE)).expectNext(true).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldAnswerFalse_whenTheNoticeWasRecordedBefore() {
        when(mongoRepository.insert(any(BirthdayNoticeEntity.class))).thenReturn(Mono.error(new DuplicateKeyException("_id")));

        StepVerifier.create(outbox.record(NOTICE)).expectNext(false).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void record_shouldPassAnyOtherFailureOn() {
        when(mongoRepository.insert(any(BirthdayNoticeEntity.class)))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(outbox.record(NOTICE)).expectError(DataAccessResourceFailureException.class).verify(Duration.ofSeconds(10));
    }

    @Test
    void findPending_shouldMapTheUnsentNotices() {
        BirthdayNoticeEntity entity = Mappers.getMapper(BirthdayNoticePersistenceMapper.class).toEntity(NOTICE, NOW);
        when(mongoRepository.findPending(Limit.of(10))).thenReturn(Flux.just(entity));

        StepVerifier.create(outbox.findPending(10)).expectNext(NOTICE).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void markPublished_shouldMarkTheNoticeByItsKey() {
        when(mongoRepository.markPublished("lea:bob:2027:FRIEND_BIRTHDAY")).thenReturn(Mono.just(1L));

        StepVerifier.create(outbox.markPublished("lea:bob:2027:FRIEND_BIRTHDAY")).expectComplete().verify(Duration.ofSeconds(10));
        verify(mongoRepository).markPublished("lea:bob:2027:FRIEND_BIRTHDAY");
    }

    @Test
    void forget_shouldDropTheUnsentNoticesConcerningTheUser() {
        when(mongoRepository.deletePendingConcerning("lea")).thenReturn(Mono.just(3L));

        StepVerifier.create(outbox.forget("lea")).expectComplete().verify(Duration.ofSeconds(10));
        verify(mongoRepository).deletePendingConcerning("lea");
    }

    @Test
    void countPending_shouldCountWhatIsLeftToSend_onTheServer() {
        when(mongoRepository.countPending()).thenReturn(Mono.just(7L));

        StepVerifier.create(outbox.countPending()).expectNext(7L).expectComplete().verify(Duration.ofSeconds(10));
    }
}
