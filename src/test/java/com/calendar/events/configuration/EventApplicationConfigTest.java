package com.calendar.events.configuration;

import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.services.EventService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EventApplicationConfigTest {

    private final EventApplicationConfig config = new EventApplicationConfig();

    @Test
    void eventService_shouldBuildTheDomainServiceFromItsPort() {
        EventRepository repository = mock(EventRepository.class);

        EventService service = config.eventService(repository);

        // Le service de domaine est instancié à la main, sans annotation Spring :
        // c'est ce qui le rend testable hors contexte.
        assertThat(service).isNotNull();
    }
}
