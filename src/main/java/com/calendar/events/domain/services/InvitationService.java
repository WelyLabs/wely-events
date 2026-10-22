package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.EventsActivity;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Set;

/**
 * Invitations, one friend at a time. An invitee sees the event whatever its visibility, a PRIVATE
 * one included, and may join it, which answers the invitation, or decline it. Only the organizer
 * invites, and only their friends; the invitee hears of it through a message the app sends.
 * Who may see an event is decided by {@link EventService}, so the rule stays in one place.
 */
public class InvitationService {

    private final EventRepository eventRepository;
    private final EventService eventService;
    private final SocialGraph socialGraph;
    private final Clock clock;
    private final EventsActivity activity;

    public InvitationService(EventRepository eventRepository, EventService eventService, SocialGraph socialGraph,
                             Clock clock, EventsActivity activity) {
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.socialGraph = socialGraph;
        this.clock = clock;
        this.activity = activity;
    }

    /**
     * Invites a friend of the organizer's to an event of theirs, and returns who takes part and is
     * invited, as the organizer sees it. Inviting twice is one invitation. Not to a community's event,
     * which its members already see, nor to one that is over, nor whoever it is hidden from.
     */
    public Mono<Participants> invite(String eventId, String organizerId, String inviteeId) {
        return eventService.requireOrganizer(eventId, organizerId)
                .flatMap(event -> {
                    if (!mayInvite(event, inviteeId)) {
                        return Mono.error(new BusinessException(BusinessErrorCode.INVITATION_NOT_ALLOWED));
                    }
                    if (event.involves(inviteeId)) {
                        return Mono.error(new BusinessException(BusinessErrorCode.ALREADY_TAKING_PART));
                    }
                    return socialGraph.friendIdsOf(organizerId)
                            .defaultIfEmpty(Set.of())
                            .flatMap(friendIds -> friendIds.contains(inviteeId)
                                    ? eventRepository.invite(event.getId(), organizerId, inviteeId)
                                            .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND)))
                                            .doOnNext(updated -> activity.invitationSent())
                                            .map(updated -> Participants.seenBy(updated, organizerId, friendIds))
                                    : Mono.error(new BusinessException(BusinessErrorCode.FRIEND_NOT_FOUND)));
                });
    }

    /**
     * Withdraws an invitation: the organizer takes it back, or the invitee declines it. Nobody
     * else may. Withdrawing one already gone changes nothing.
     */
    public Mono<Void> withdraw(String eventId, String requesterId, String inviteeId) {
        return eventService.getEventById(eventId, requesterId)
                .flatMap(event -> requesterId.equals(event.getOrganizerId()) || requesterId.equals(inviteeId)
                        ? eventRepository.withdrawInvitation(event.getId(), inviteeId)
                                .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND)))
                                .then()
                        : Mono.error(new BusinessException(BusinessErrorCode.EVENT_ACCESS_DENIED)));
    }

    private boolean mayInvite(Event event, String inviteeId) {
        return !event.isCommunityEvent()
                && !event.isHiddenFrom(inviteeId)
                // A surprise is not invited to at all: the honoree's friends see it and join it
                // themselves, their plain friends asking the organizer first. Inviting would only
                // duplicate that -- and would have needed the honoree's circle to be readable.
                && !event.isSurprise()
                && !event.isOverAt(clock.instant());
    }
}
