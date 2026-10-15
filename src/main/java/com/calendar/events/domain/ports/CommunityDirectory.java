package com.calendar.events.domain.ports;

import com.calendar.events.domain.models.CommunityRole;
import reactor.core.publisher.Mono;

/** Who belongs to which community, as wely-social knows it. */
public interface CommunityDirectory {

    /** The user's role in the community; empty when they are not a member, or it does not exist. */
    Mono<CommunityRole> roleOf(String communityId, String userId);
}
