package com.calendar.events.infrastructure.messaging.mappers;

import com.calendar.events.domain.models.FriendshipNotice;
import com.calendar.events.infrastructure.messaging.models.FriendshipNoticeDTO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FriendshipNoticeMapper {

    // MapStruct writes an Instant as its toString(): ISO-8601, whatever the converter's date settings.
    FriendshipNoticeDTO toDto(FriendshipNotice notice);
}
