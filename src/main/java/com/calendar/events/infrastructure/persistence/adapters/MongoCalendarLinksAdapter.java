package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.CalendarLink;
import com.calendar.events.domain.ports.CalendarLinks;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.persistence.mappers.CalendarLinkPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveCalendarLinkMongoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** The {@code calendar_links} collection, whose store failures become STORE_UNAVAILABLE. */
@Slf4j
@Component
public class MongoCalendarLinksAdapter implements CalendarLinks {

    private final ReactiveCalendarLinkMongoRepository mongoRepository;
    private final CalendarLinkPersistenceMapper mapper;

    public MongoCalendarLinksAdapter(ReactiveCalendarLinkMongoRepository mongoRepository,
                                     CalendarLinkPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    /** An insert: a token is random and never reused. */
    @Override
    public Mono<CalendarLink> save(CalendarLink link) {
        return mongoRepository.insert(mapper.toEntity(link))
                .map(mapper::toDomain)
                .onErrorMap(DataAccessException.class, error -> unavailable("save", error));
    }

    @Override
    public Mono<CalendarLink> findByToken(String token) {
        return mongoRepository.findById(token)
                .map(mapper::toDomain)
                .onErrorMap(DataAccessException.class, error -> unavailable("read", error));
    }

    private static EventException unavailable(String operation, DataAccessException error) {
        log.warn("Calendar links: {} failed: {}", operation, error.getClass().getSimpleName());
        return new EventException(EventErrorCode.STORE_UNAVAILABLE);
    }
}
