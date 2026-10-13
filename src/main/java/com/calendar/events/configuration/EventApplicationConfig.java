package com.calendar.events.configuration;

import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.ExpenseRepository;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.ExpenseService;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.domain.services.FriendCalendarService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class EventApplicationConfig {

    @Bean
    public EventService eventService(EventRepository eventRepository, SocialGraph socialGraph,
                                     ExpenseRepository expenseRepository) {
        return new EventService(eventRepository, socialGraph, expenseRepository, Clock.systemUTC());
    }

    @Bean
    public ExpenseService expenseService(EventRepository eventRepository, ExpenseRepository expenseRepository) {
        return new ExpenseService(eventRepository, expenseRepository, Clock.systemUTC());
    }

    @Bean
    public FriendCalendarService friendCalendarService(EventRepository eventRepository, SocialGraph socialGraph) {
        return new FriendCalendarService(eventRepository, socialGraph);
    }

    @Bean
    public FreeSlotService freeSlotService(EventRepository eventRepository, SocialGraph socialGraph) {
        return new FreeSlotService(eventRepository, socialGraph);
    }
}
