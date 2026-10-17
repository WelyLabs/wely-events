package com.calendar.events.configuration;

import com.calendar.events.domain.ports.CommunityDirectory;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.DeletedUsersSource;
import com.calendar.events.domain.ports.EventDeletionOutbox;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.domain.services.AccountErasureService;
import com.calendar.events.domain.services.AccountGuard;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.ExpenseService;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.domain.services.FriendCalendarService;
import com.calendar.events.domain.services.FriendOverviewService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.PollService;
import com.calendar.events.domain.services.ProfileSummaryService;
import com.calendar.events.domain.services.ScheduleService;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EventApplicationConfigTest {

    private final EventApplicationConfig config = new EventApplicationConfig();

    @Test
    void eventService_shouldBuildTheDomainServiceFromItsPort() {
        EventRepository repository = mock(EventRepository.class);

        EventService service = config.eventService(repository, mock(SocialGraph.class), mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)), mock(EventDeletionOutbox.class), mock(CommunityDirectory.class),
                guard());

        // The domain service is built by hand, with no Spring annotation: that is what
        // makes it testable outside a context.
        assertThat(service).isNotNull();
    }

    @Test
    void expenseService_shouldBuildTheDomainServiceFromItsPorts() {
        ExpenseService service = config.expenseService(mock(EventRepository.class), mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)), guard());

        assertThat(service).isNotNull();
    }

    @Test
    void scheduleService_shouldBuildTheDomainServiceFromItsPortAndTheEventService() {
        EventRepository repository = mock(EventRepository.class);
        EventService eventService = config.eventService(repository, mock(SocialGraph.class),
                mock(ExpenseRepository.class), config.pictureService(mock(MediaStore.class)), mock(EventDeletionOutbox.class), mock(CommunityDirectory.class),
                guard());

        ScheduleService service = config.scheduleService(repository, eventService);

        assertThat(service).isNotNull();
    }

    @Test
    void pictureService_shouldBuildTheDomainServiceFromItsPort() {
        PictureService service = config.pictureService(mock(MediaStore.class));

        assertThat(service).isNotNull();
    }

    @Test
    void profileSummaryService_shouldBuildTheDomainServiceFromItsPorts() {
        ProfileSummaryService service = config.profileSummaryService(mock(EventRepository.class), mock(ExpenseRepository.class));

        assertThat(service).isNotNull();
        // A profile's "this year" starts at midnight in France, not in UTC.
        assertThat(EventApplicationConfig.USERS_ZONE).hasToString("Europe/Paris");
    }

    @Test
    void freeSlotService_shouldBuildTheDomainServiceFromItsPorts() {
        FreeSlotService service = config.freeSlotService(mock(EventRepository.class), mock(SocialGraph.class));

        assertThat(service).isNotNull();
    }

    @Test
    void friendCalendarService_shouldBuildTheDomainServiceFromItsPorts() {
        FriendCalendarService service = config.friendCalendarService(mock(EventRepository.class), mock(SocialGraph.class));

        assertThat(service).isNotNull();
    }

    @Test
    void friendOverviewService_shouldBuildTheDomainServiceFromItsPorts() {
        FriendOverviewService service = config.friendOverviewService(mock(EventRepository.class), mock(SocialGraph.class));

        assertThat(service).isNotNull();
    }

    @Test
    void pollService_shouldBuildTheDomainServiceFromItsPorts() {
        EventRepository repository = mock(EventRepository.class);
        EventService eventService = config.eventService(repository, mock(SocialGraph.class), mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)), mock(EventDeletionOutbox.class), mock(CommunityDirectory.class),
                guard());

        PollService service = config.pollService(repository, eventService, mock(PollUpdates.class), guard());

        assertThat(service).isNotNull();
    }

    @Test
    void accountGuard_shouldBuildTheDomainServiceFromItsPort() {
        assertThat(config.accountGuard(mock(DeletedUserMarkers.class))).isNotNull();
    }

    @Test
    void accountErasureService_shouldBuildTheDomainServiceFromItsPorts() {
        EventRepository repository = mock(EventRepository.class);
        EventService eventService = config.eventService(repository, mock(SocialGraph.class), mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)), mock(EventDeletionOutbox.class), mock(CommunityDirectory.class),
                guard());

        AccountErasureService service = config.accountErasureService(repository, eventService,
                mock(com.calendar.events.domain.services.BirthdayService.class), mock(ExpenseRepository.class), mock(MediaStore.class), mock(DeletedUserMarkers.class),
                mock(PollUpdates.class), "89a380b8-f66a-44d0-b9d9-616241080430");

        assertThat(service).isNotNull();
    }

    @Test
    void accountReconciliationService_shouldBuildTheDomainServiceFromItsPorts() {
        assertThat(config.accountReconciliationService(mock(DeletedUsersSource.class), mock(DeletedUserMarkers.class),
                mock(AccountErasureService.class), 500, Duration.ofHours(48))).isNotNull();
    }

    @Test
    void parseIds_shouldTrimLowercaseAndDropBlanks() {
        assertThat(EventApplicationConfig.parseIds(" 89A380B8-F66A-44D0-B9D9-616241080430 ,, b2 ,"))
                .containsExactlyInAnyOrder("89a380b8-f66a-44d0-b9d9-616241080430", "b2");
    }

    @Test
    void parseIds_shouldBeEmpty_whenNothingIsConfigured() {
        assertThat(EventApplicationConfig.parseIds("")).isEmpty();
        assertThat(EventApplicationConfig.parseIds("  ")).isEmpty();
        assertThat(EventApplicationConfig.parseIds(null)).isEmpty();
    }

    private static AccountGuard guard() {
        return new AccountGuard(mock(DeletedUserMarkers.class));
    }

    @Test
    void clock_shouldBeTheUtcSystemClock() {
        assertThat(config.clock().getZone()).isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    void birthdayServices_shouldBuildTheDomainServicesFromTheirPorts() {
        EventRepository repository = mock(EventRepository.class);
        SocialGraph socialGraph = mock(SocialGraph.class);
        java.time.Clock clock = config.clock();
        EventService eventService = config.eventService(repository, socialGraph, mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)), mock(EventDeletionOutbox.class), mock(CommunityDirectory.class),
                guard());
        com.calendar.events.domain.ports.BirthdayNoticeOutbox outbox = mock(com.calendar.events.domain.ports.BirthdayNoticeOutbox.class);
        com.calendar.events.domain.services.FriendDirectory directory = config.friendDirectory(socialGraph,
                mock(com.calendar.events.domain.ports.FriendLists.class), clock);

        assertThat(directory).isNotNull();
        assertThat(config.birthdayService(repository, eventService, directory, outbox, guard())).isNotNull();
        assertThat(config.surpriseService(eventService, repository, socialGraph, directory, outbox, clock)).isNotNull();
        assertThat(config.birthdayReminderService(repository, directory, outbox, guard(), clock)).isNotNull();
    }
}
