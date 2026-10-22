package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.EventKind;
import com.calendar.events.domain.models.Participants;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.models.Visibility;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.RecordingEventsActivity;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvitationServiceTest {

    private final RecordingEventsActivity activity = new RecordingEventsActivity();

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final String ORGANIZER = "robin";
    private static final String FRIEND = "lea";

    @Mock
    private EventRepository eventRepository;
    @Mock
    private EventService eventService;
    @Mock
    private SocialGraph socialGraph;

    private InvitationService service;

    @BeforeEach
    void setUp() {
        service = new InvitationService(eventRepository, eventService, socialGraph, Clock.fixed(NOW, ZoneOffset.UTC), activity);
    }

    private static Event dinner() {
        return Event.builder().id("e1").title("Dîner").organizerId(ORGANIZER).visibility(Visibility.PRIVATE)
                .startDate(NOW.plus(Duration.ofDays(2))).participantIds(new ArrayList<>(List.of(ORGANIZER))).build();
    }

    private static Event invited(Event event, String... invitees) {
        event.setInvitedIds(new ArrayList<>(List.of(invitees)));
        return event;
    }

    private static boolean hasCode(Throwable error, BusinessErrorCode code) {
        return error instanceof BusinessException e && e.getErrorCode() == code;
    }

    private void organizes(Event event) {
        when(eventService.requireOrganizer("e1", ORGANIZER)).thenReturn(Mono.just(event));
    }

    private void expectInviteRefused(BusinessErrorCode code) {
        StepVerifier.create(service.invite("e1", ORGANIZER, FRIEND))
                .expectErrorMatches(error -> hasCode(error, code))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).invite(anyString(), anyString(), anyString());
    }

    // --- invite ------------------------------------------------------------------------------------

    @Test
    void invite_shouldInviteAFriend_andAnswerWithTheInvitationsAsTheOrganizerSeesThem() {
        organizes(dinner());
        when(socialGraph.friendIdsOf(ORGANIZER)).thenReturn(Mono.just(Set.of(FRIEND)));
        when(eventRepository.invite("e1", ORGANIZER, FRIEND)).thenReturn(Mono.just(invited(dinner(), FRIEND)));

        StepVerifier.create(service.invite("e1", ORGANIZER, FRIEND))
                .expectNext(new Participants(1, List.of(ORGANIZER), List.of(), List.of(FRIEND), List.of()))
                .expectComplete().verify(Duration.ofSeconds(10));
        assertThat(activity.invitationsSent).hasValue(1);
    }

    @Test
    void invite_shouldRefuseSomeoneNotTheOrganizersFriend() {
        organizes(dinner());
        when(socialGraph.friendIdsOf(ORGANIZER)).thenReturn(Mono.just(Set.of("hugo")));

        expectInviteRefused(BusinessErrorCode.FRIEND_NOT_FOUND);
        assertThat(activity.isSilent()).isTrue();
    }

    @Test
    void invite_shouldRefuse_whenTheFriendsCannotBeRead() {
        organizes(dinner());
        when(socialGraph.friendIdsOf(ORGANIZER)).thenReturn(Mono.empty());

        expectInviteRefused(BusinessErrorCode.FRIEND_NOT_FOUND);
        assertThat(activity.isSilent()).isTrue();
    }

    @Test
    void invite_shouldRefuseSomeoneAlreadyTakingPart() {
        Event event = dinner();
        event.getParticipantIds().add(FRIEND);
        organizes(event);

        expectInviteRefused(BusinessErrorCode.ALREADY_TAKING_PART);
        assertThat(activity.isSilent()).isTrue();
    }

    @Test
    void invite_shouldRefuseACommunityEvent() {
        Event event = dinner();
        event.setCommunityId("club");
        organizes(event);

        expectInviteRefused(BusinessErrorCode.INVITATION_NOT_ALLOWED);
    }

    @Test
    void invite_shouldRefuseAnEventThatIsOver() {
        Event event = dinner();
        event.setStartDate(NOW.minus(Duration.ofDays(1)));
        organizes(event);

        expectInviteRefused(BusinessErrorCode.INVITATION_NOT_ALLOWED);
    }

    @Test
    void invite_shouldAcceptAnEventWhoseDateIsStillPolled_evenIfItsEarliestProposalIsPast() {
        Event event = dinner();
        event.setStartDate(NOW.minus(Duration.ofDays(1)));
        event.setPoll(new Poll(PollStatus.OPEN, List.of(new PollSlot("s1", NOW.plus(Duration.ofDays(3)), null)),
                Map.of(), null));
        organizes(event);
        when(socialGraph.friendIdsOf(ORGANIZER)).thenReturn(Mono.just(Set.of(FRIEND)));
        when(eventRepository.invite("e1", ORGANIZER, FRIEND)).thenReturn(Mono.just(invited(event, FRIEND)));

        StepVerifier.create(service.invite("e1", ORGANIZER, FRIEND))
                .expectNextMatches(participants -> participants.invitedIds().equals(List.of(FRIEND)))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void invite_shouldRefuseWhoeverTheEventIsHiddenFrom() {
        Event event = dinner();
        event.setHiddenFrom(List.of(FRIEND));
        organizes(event);

        expectInviteRefused(BusinessErrorCode.INVITATION_NOT_ALLOWED);
    }

    @Test
    void invite_shouldRefuseASurpriseAltogether() {
        // Found, not offered: the honoree's friends see it and join it themselves.
        Event event = dinner();
        event.setKind(EventKind.SURPRISE);
        event.setHonoreeId("camille");
        organizes(event);

        expectInviteRefused(BusinessErrorCode.INVITATION_NOT_ALLOWED);
    }

    @Test
    void invite_shouldRefuseASurpriseEvenForTheOrganizersOwnFriend() {
        Event event = dinner();
        event.setKind(EventKind.SURPRISE);
        event.setHonoreeId("camille");
        organizes(event);

        expectInviteRefused(BusinessErrorCode.INVITATION_NOT_ALLOWED);
        verify(socialGraph, never()).friendIdsOf(anyString());
    }

    @Test
    void invite_shouldAnswerNotFound_whenTheEventWentMeanwhile() {
        organizes(dinner());
        when(socialGraph.friendIdsOf(ORGANIZER)).thenReturn(Mono.just(Set.of(FRIEND)));
        when(eventRepository.invite("e1", ORGANIZER, FRIEND)).thenReturn(Mono.empty());

        StepVerifier.create(service.invite("e1", ORGANIZER, FRIEND))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
        assertThat(activity.isSilent()).isTrue();
    }

    @Test
    void invite_shouldLetTheOrganizerCheckThrough() {
        when(eventService.requireOrganizer("e1", FRIEND))
                .thenReturn(Mono.error(new BusinessException(BusinessErrorCode.EVENT_ACCESS_DENIED)));

        StepVerifier.create(service.invite("e1", FRIEND, "hugo"))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_ACCESS_DENIED))
                .verify(Duration.ofSeconds(10));
    }

    // --- withdraw ----------------------------------------------------------------------------------

    @Test
    void withdraw_shouldLetTheOrganizerTakeAnInvitationBack() {
        when(eventService.getEventById("e1", ORGANIZER)).thenReturn(Mono.just(invited(dinner(), FRIEND)));
        when(eventRepository.withdrawInvitation("e1", FRIEND)).thenReturn(Mono.just(dinner()));

        StepVerifier.create(service.withdraw("e1", ORGANIZER, FRIEND)).expectComplete().verify(Duration.ofSeconds(10));
        verify(eventRepository).withdrawInvitation("e1", FRIEND);
    }

    @Test
    void withdraw_shouldLetTheInviteeDecline() {
        when(eventService.getEventById("e1", FRIEND)).thenReturn(Mono.just(invited(dinner(), FRIEND)));
        when(eventRepository.withdrawInvitation("e1", FRIEND)).thenReturn(Mono.just(dinner()));

        StepVerifier.create(service.withdraw("e1", FRIEND, FRIEND)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void withdraw_shouldRefuseAnyoneElse() {
        Event event = invited(dinner(), FRIEND);
        event.getParticipantIds().add("hugo");
        when(eventService.getEventById("e1", "hugo")).thenReturn(Mono.just(event));

        StepVerifier.create(service.withdraw("e1", "hugo", FRIEND))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_ACCESS_DENIED))
                .verify(Duration.ofSeconds(10));
        verify(eventRepository, never()).withdrawInvitation(anyString(), anyString());
    }

    @Test
    void withdraw_shouldAnswerNotFound_whenTheEventWentMeanwhile() {
        when(eventService.getEventById("e1", FRIEND)).thenReturn(Mono.just(invited(dinner(), FRIEND)));
        when(eventRepository.withdrawInvitation("e1", FRIEND)).thenReturn(Mono.empty());

        StepVerifier.create(service.withdraw("e1", FRIEND, FRIEND))
                .expectErrorMatches(error -> hasCode(error, BusinessErrorCode.EVENT_NOT_FOUND))
                .verify(Duration.ofSeconds(10));
    }
}
