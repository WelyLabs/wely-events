package com.calendar.events.infrastructure.persistence.repositories;

import com.calendar.events.infrastructure.persistence.entities.CalendarLinkEntity;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReactiveCalendarLinkMongoRepository extends ReactiveMongoRepository<CalendarLinkEntity, String> {
}
