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
import com.calendar.events.domain.services.FriendOverviewService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.PollService;
import com.calendar.events.domain.services.ProfileSummaryService;
import com.calendar.events.domain.services.ScheduleService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class EventApplicationConfig {

    /** Wely's users are in France: "this year" and "this month" on a profile start at midnight there. */
    static final ZoneId USERS_ZONE = ZoneId.of("Europe/Paris");

    @Bean
    public PictureService pictureService(MediaStore mediaStore) {
        return new PictureService(mediaStore);
    }

    @Bean
    public EventService eventService(EventRepository eventRepository, SocialGraph socialGraph,
                                     ExpenseRepository expenseRepository, PictureService pictureService) {
        return new EventService(eventRepository, socialGraph, expenseRepository, pictureService, Clock.systemUTC());
    }

    @Bean
    public PollService pollService(EventRepository eventRepository, EventService eventService, PollUpdates pollUpdates) {
        return new PollService(eventRepository, eventService, pollUpdates);
    }

    @Bean
    public ScheduleService scheduleService(EventRepository eventRepository, EventService eventService) {
        return new ScheduleService(eventRepository, eventService, Clock.systemUTC(), USERS_ZONE);
    }

    @Bean
    public ExpenseService expenseService(EventRepository eventRepository, ExpenseRepository expenseRepository,
                                         PictureService pictureService) {
        return new ExpenseService(eventRepository, expenseRepository, pictureService, Clock.systemUTC());
    }

    @Bean
    public FriendCalendarService friendCalendarService(EventRepository eventRepository, SocialGraph socialGraph) {
        return new FriendCalendarService(eventRepository, socialGraph);
    }

    @Bean
    public FriendOverviewService friendOverviewService(EventRepository eventRepository, SocialGraph socialGraph) {
        return new FriendOverviewService(eventRepository, socialGraph, Clock.systemUTC());
    }

    @Bean
    public FreeSlotService freeSlotService(EventRepository eventRepository, SocialGraph socialGraph) {
        return new FreeSlotService(eventRepository, socialGraph);
    }

    @Bean
    public ProfileSummaryService profileSummaryService(EventRepository eventRepository, ExpenseRepository expenseRepository) {
        return new ProfileSummaryService(eventRepository, expenseRepository, Clock.systemUTC(), USERS_ZONE);
    }
}
