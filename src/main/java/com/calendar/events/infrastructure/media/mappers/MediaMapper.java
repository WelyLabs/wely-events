package com.calendar.events.infrastructure.media.mappers;

import com.calendar.events.domain.models.StoredMedia;
import com.calendar.events.infrastructure.media.models.StoredMediaResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Translates wely-media's answers into the domain. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MediaMapper {

    StoredMedia toStoredMedia(StoredMediaResponse response);
}
