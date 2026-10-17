package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.domain.ports.BirthdayNoticeOutbox;
import com.calendar.events.infrastructure.persistence.mappers.BirthdayNoticePersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveBirthdayNoticeMongoRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;

/** The {@code birthday_notices} collection: an insert on the notice's key, which a second insert hits. */
@Component
public class MongoBirthdayNoticeOutboxAdapter implements BirthdayNoticeOutbox {

    private final ReactiveBirthdayNoticeMongoRepository mongoRepository;
    private final BirthdayNoticePersistenceMapper mapper;
    private final Clock clock;

    public MongoBirthdayNoticeOutboxAdapter(ReactiveBirthdayNoticeMongoRepository mongoRepository,
                                            BirthdayNoticePersistenceMapper mapper, Clock clock) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Mono<Boolean> record(BirthdayNotice notice) {
        return Mono.defer(() -> mongoRepository.insert(mapper.toEntity(notice, clock.instant())))
                .thenReturn(true)
                .onErrorResume(DuplicateKeyException.class, alreadyRecorded -> Mono.just(false));
    }

    @Override
    public Flux<BirthdayNotice> findPending(int limit) {
        return mongoRepository.findPending(Limit.of(limit)).map(mapper::toDomain);
    }

    @Override
    public Mono<Void> markPublished(String key) {
        return mongoRepository.markPublished(key).then();
    }

    @Override
    public Mono<Void> forget(String userId) {
        return mongoRepository.deletePendingConcerning(userId).then();
    }
}
