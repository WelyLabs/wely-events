package com.calendar.events.configuration;

import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendDirectory;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.FreeSlotService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventApplicationConfig {

    @Bean
    public EventService eventService(EventRepository eventRepository) {
        return new EventService(eventRepository);
    }

    @Bean
    public FreeSlotService freeSlotService(EventRepository eventRepository, FriendDirectory friendDirectory) {
        return new FreeSlotService(eventRepository, friendDirectory);
    }
}
