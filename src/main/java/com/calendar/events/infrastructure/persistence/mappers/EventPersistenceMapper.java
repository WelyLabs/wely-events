package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import com.calendar.events.infrastructure.persistence.entities.PollEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

import java.util.Map;

@Mapper(componentModel = "spring")
public interface EventPersistenceMapper {

    Event toDomain(EventEntity entity);

    // Only written by the participant updates: a save never resets them, so a new event starts unsynced.
    @Mapping(target = "groupVersion", ignore = true)
    @Mapping(target = "groupSynced", ignore = true)
    // Only written by the date fix, for its relay.
    @Mapping(target = "dateFixSynced", ignore = true)
    EventEntity toEntity(Event domain);

    PollEntity toEntity(Poll poll);

    Poll toDomain(PollEntity entity);

    // Nested maps, per voter then per slot; the generated maps keep insertion order (voting order).
    Map<String, Map<String, String>> toEntityBallots(Map<String, Map<String, PollAnswer>> ballots);

    Map<String, Map<String, PollAnswer>> toDomainBallots(Map<String, Map<String, String>> ballots);

    Map<String, String> toStoredAnswers(Map<String, PollAnswer> answers);

    Map<String, PollAnswer> toAnswers(Map<String, String> answers);

    // GeoJSON orders a point (longitude, latitude): the reverse of how people say it.
    default GeoJsonPoint toGeoJson(GeoPoint point) {
        return point == null ? null : new GeoJsonPoint(point.longitude(), point.latitude());
    }

    default GeoPoint toGeoPoint(GeoJsonPoint point) {
        return point == null ? null : new GeoPoint(point.getY(), point.getX());
    }
}
