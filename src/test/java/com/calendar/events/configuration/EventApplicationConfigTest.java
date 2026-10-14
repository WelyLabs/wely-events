package com.calendar.events.configuration;

import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.ExpenseService;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.domain.services.FriendCalendarService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.PollService;
import com.calendar.events.domain.services.ProfileSummaryService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EventApplicationConfigTest {

    private final EventApplicationConfig config = new EventApplicationConfig();

    @Test
    void eventService_shouldBuildTheDomainServiceFromItsPort() {
        EventRepository repository = mock(EventRepository.class);

        EventService service = config.eventService(repository, mock(SocialGraph.class), mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)));

        // The domain service is built by hand, with no Spring annotation: that is what
        // makes it testable outside a context.
        assertThat(service).isNotNull();
    }

    @Test
    void expenseService_shouldBuildTheDomainServiceFromItsPorts() {
        ExpenseService service = config.expenseService(mock(EventRepository.class), mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)));

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
    void pollService_shouldBuildTheDomainServiceFromItsPorts() {
        EventRepository repository = mock(EventRepository.class);
        EventService eventService = config.eventService(repository, mock(SocialGraph.class), mock(ExpenseRepository.class),
                config.pictureService(mock(MediaStore.class)));

        PollService service = config.pollService(repository, eventService, mock(PollUpdates.class));

        assertThat(service).isNotNull();
    }
}
