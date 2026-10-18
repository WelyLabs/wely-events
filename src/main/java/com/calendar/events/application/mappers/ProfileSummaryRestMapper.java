package com.calendar.events.application.mappers;

import com.calendar.events.application.dtos.ProfileSummaryResponse;
import com.calendar.events.domain.models.ProfileSummary;
import com.calendar.events.domain.models.SignedUrls;
import com.calendar.events.domain.models.ViewerFriends;
import org.mapstruct.Context;
import org.mapstruct.Mapper;

/** Events go through {@link EventRestMapper}, so a profile shows them exactly as the feed does. */
@Mapper(componentModel = "spring", uses = EventRestMapper.class)
public interface ProfileSummaryRestMapper {

    ProfileSummaryResponse toResponse(ProfileSummary summary, @Context SignedUrls urls, @Context ViewerFriends friends);
}
