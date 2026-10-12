package com.calendar.events.infrastructure.persistence.mappers;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.GeoPoint;
import com.calendar.events.infrastructure.persistence.entities.EventEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

@Mapper(componentModel = "spring")
public interface EventPersistenceMapper {

    Event toDomain(EventEntity entity);

    // Only written by the participant updates: a save never resets them, so a new event starts unsynced.
    @Mapping(target = "groupVersion", ignore = true)
    @Mapping(target = "groupSynced", ignore = true)
    EventEntity toEntity(Event domain);

    // GeoJSON orders a point (longitude, latitude): the reverse of how people say it.
    default GeoJsonPoint toGeoJson(GeoPoint point) {
        return point == null ? null : new GeoJsonPoint(point.longitude(), point.latitude());
    }

    default GeoPoint toGeoPoint(GeoJsonPoint point) {
        return point == null ? null : new GeoPoint(point.getY(), point.getX());
    }
}
