package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.persistence.entities.CalendarLinkEntity;
import com.calendar.events.infrastructure.persistence.mappers.CalendarLinkPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveCalendarLinkMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoCalendarLinksAdapterTest {

    private static final Instant EXPIRY = Instant.parse("2026-10-17T10:05:00Z");
    private static final CalendarLink LINK = new CalendarLink("tok", "e1", "https://w/event/e1", EXPIRY);

    @Mock
    private ReactiveCalendarLinkMongoRepository mongoRepository;

    private final CalendarLinkPersistenceMapper mapper = Mappers.getMapper(CalendarLinkPersistenceMapper.class);

    private MongoCalendarLinksAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoCalendarLinksAdapter(mongoRepository, mapper);
    }

    private static boolean storeUnavailable(Throwable error) {
        return error instanceof EventException e && e.getErrorCode() == EventErrorCode.STORE_UNAVAILABLE;
    }

    @Test
    void save_shouldInsertTheLink() {
        when(mongoRepository.insert(any(CalendarLinkEntity.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(adapter.save(LINK))
                .expectNext(LINK)
                .verifyComplete();
    }

    @Test
    void save_shouldReportTheStoreUnavailable() {
        when(mongoRepository.insert(any(CalendarLinkEntity.class)))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.save(LINK))
                .expectErrorMatches(MongoCalendarLinksAdapterTest::storeUnavailable)
                .verify();
    }

    @Test
    void findByToken_shouldReadTheLink() {
        when(mongoRepository.findById("tok")).thenReturn(Mono.just(mapper.toEntity(LINK)));

        StepVerifier.create(adapter.findByToken("tok"))
                .expectNext(LINK)
                .verifyComplete();
    }

    @Test
    void findByToken_shouldBeEmpty_forAnUnknownToken() {
        when(mongoRepository.findById("nope")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.findByToken("nope"))
                .verifyComplete();
    }

    @Test
    void findByToken_shouldReportTheStoreUnavailable() {
        when(mongoRepository.findById("tok")).thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.findByToken("tok"))
                .expectErrorMatches(MongoCalendarLinksAdapterTest::storeUnavailable)
                .verify();
    }
}
