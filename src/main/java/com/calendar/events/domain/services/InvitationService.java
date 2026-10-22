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
 * invites; the invitee hears of it through a message the app sends. Who may see an event is decided
 * by {@link EventService}, so the rule stays in one place.
 *
 * <p>Who may be invited depends on the kind: the organizer's friends on a standard event, and the
 * HONOREE's friends on a SURPRISE -- a surprise gathers the people whose birthday it is, not the
 * organizer's own circle.
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
                    // The organizer's friends are read whatever the kind: the answer shows which of
                    // them take part, which is the organizer's view and not the guest rule.
                    return socialGraph.friendIdsOf(organizerId)
                            .defaultIfEmpty(Set.of())
                            .flatMap(organizerFriends -> mayBeInvitedTo(event, organizerFriends)
                                    .flatMap(guests -> guests.contains(inviteeId)
                                            ? eventRepository.invite(event.getId(), organizerId, inviteeId)
                                                    .switchIfEmpty(Mono.error(() -> new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND)))
                                                    .doOnNext(updated -> activity.invitationSent())
                                                    .map(updated -> Participants.seenBy(updated, organizerId, organizerFriends))
                                            : Mono.error(new BusinessException(BusinessErrorCode.FRIEND_NOT_FOUND))));
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

    /**
     * Who may be invited: the organizer's friends, or the honoree's on a SURPRISE. A refusal answers
     * the same FRIEND_NOT_FOUND as elsewhere, on purpose: a 403 naming the reason would let an
     * organizer read the honoree's friend list one id at a time.
     */
    private Mono<Set<String>> mayBeInvitedTo(Event event, Set<String> organizerFriends) {
        return event.isSurprise()
                ? socialGraph.friendIdsOf(event.getHonoreeId()).defaultIfEmpty(Set.of())
                : Mono.just(organizerFriends);
    }

    private boolean mayInvite(Event event, String inviteeId) {
        return !event.isCommunityEvent()
                && !event.isHiddenFrom(inviteeId)
                && !(event.isSurprise() && inviteeId.equals(event.getHonoreeId()))
                && !event.isOverAt(clock.instant());
    }
}
