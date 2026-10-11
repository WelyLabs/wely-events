package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.TimeRangeResponse;
import com.calendar.events.domain.models.TimeRange;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FreeSlotRestMapper {

    TimeRangeResponse toResponse(TimeRange range);
}
