package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.FriendOverviewResponse;
import com.calendar.events.domain.models.FriendOverview;
import com.calendar.events.domain.models.SignedUrls;
import org.mapstruct.Context;
import org.mapstruct.Mapper;

/** Events go through {@link EventRestMapper}, so a friend's page shows them exactly as the feed does. */
@Mapper(componentModel = "spring", uses = EventRestMapper.class)
public interface FriendOverviewRestMapper {

    FriendOverviewResponse toResponse(FriendOverview overview, @Context SignedUrls urls);
}
