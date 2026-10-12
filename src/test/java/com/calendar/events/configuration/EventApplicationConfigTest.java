package com.calendar.events.configuration;

import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.domain.services.FriendCalendarService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EventApplicationConfigTest {

    private final EventApplicationConfig config = new EventApplicationConfig();

    @Test
    void eventService_shouldBuildTheDomainServiceFromItsPort() {
        EventRepository repository = mock(EventRepository.class);

        EventService service = config.eventService(repository, mock(SocialGraph.class));

        // The domain service is built by hand, with no Spring annotation: that is what
        // makes it testable outside a context.
        assertThat(service).isNotNull();
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
}
