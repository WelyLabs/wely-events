package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.BirthdayOccurrenceResponse;
import com.calendar.events.domain.models.BirthdayOccurrence;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface BirthdayRestMapper {

    BirthdayOccurrenceResponse toResponse(BirthdayOccurrence occurrence);

    BirthdayOccurrenceResponse.SurpriseResponse toResponse(BirthdayOccurrence.Surprise surprise);
}
