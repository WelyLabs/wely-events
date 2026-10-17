package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.domain.models.BirthdayNotice;
import com.calendar.events.infrastructure.messaging.models.BirthdayNoticeDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BirthdayNoticeMapper {

    // ISO-8601, YYYY-MM-DD, whatever the converter's date settings.
    @Mapping(target = "date", expression = "java(notice.date().toString())")
    BirthdayNoticeDTO toDto(BirthdayNotice notice);
}
