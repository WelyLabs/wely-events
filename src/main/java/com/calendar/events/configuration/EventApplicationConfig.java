package com.calendar.events.configuration;

import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.FriendDirectory;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.domain.services.FriendCalendarService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class EventApplicationConfig {

    @Bean
    public EventService eventService(EventRepository eventRepository, FriendDirectory friendDirectory) {
        return new EventService(eventRepository, friendDirectory, Clock.systemUTC());
    }

    @Bean
    public FriendCalendarService friendCalendarService(EventRepository eventRepository, FriendDirectory friendDirectory) {
        return new FriendCalendarService(eventRepository, friendDirectory);
    }

    @Bean
    public FreeSlotService freeSlotService(EventRepository eventRepository, FriendDirectory friendDirectory) {
        return new FreeSlotService(eventRepository, friendDirectory);
    }
}
