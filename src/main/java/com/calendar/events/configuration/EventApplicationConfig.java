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
import com.calendar.events.domain.services.AccountReconciliationService;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.ExpenseService;
import com.calendar.events.domain.services.FreeSlotService;
import com.calendar.events.domain.services.FriendCalendarService;
import com.calendar.events.domain.services.FriendOverviewService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.PollService;
import com.calendar.events.domain.services.ProfileSummaryService;
import com.calendar.events.domain.services.ScheduleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class EventApplicationConfig {

    /** Wely's users are in France: "this year" and "this month" on a profile start at midnight there. */
    static final ZoneId USERS_ZONE = ZoneId.of("Europe/Paris");

    @Bean
    public PictureService pictureService(MediaStore mediaStore) {
        return new PictureService(mediaStore);
    }

    @Bean
    public AccountGuard accountGuard(DeletedUserMarkers markers) {
        return new AccountGuard(markers);
    }

    @Bean
    public EventService eventService(EventRepository eventRepository, SocialGraph socialGraph,
                                     ExpenseRepository expenseRepository, PictureService pictureService,
                                     EventDeletionOutbox deletionOutbox, CommunityDirectory communityDirectory,
                                     AccountGuard accountGuard) {
        return new EventService(eventRepository, socialGraph, expenseRepository, pictureService, deletionOutbox,
                communityDirectory, accountGuard, Clock.systemUTC());
    }

    @Bean
    public PollService pollService(EventRepository eventRepository, EventService eventService, PollUpdates pollUpdates,
                                   AccountGuard accountGuard) {
        return new PollService(eventRepository, eventService, pollUpdates, accountGuard);
    }

    /** {@code protectedUserIds}: the demo accounts, comma-separated, never erased nor handed an event. */
    @Bean
    public AccountErasureService accountErasureService(EventRepository eventRepository, EventService eventService,
                                                       ExpenseRepository expenseRepository, MediaStore mediaStore,
                                                       DeletedUserMarkers markers, PollUpdates pollUpdates,
                                                       @Value("${app.account-deletion.protected-user-ids:}") String protectedUserIds) {
        Set<String> protectedIds = parseIds(protectedUserIds);
        log.info("Account erasure: {} protected account(s)", protectedIds.size());
        return new AccountErasureService(eventRepository, eventService, expenseRepository, mediaStore, markers,
                pollUpdates, protectedIds, Clock.systemUTC());
    }

    @Bean
    public AccountReconciliationService accountReconciliationService(
            DeletedUsersSource deletedUsersSource, DeletedUserMarkers markers,
            AccountErasureService accountErasureService,
            @Value("${app.account-erasure.reconciliation.page-size:500}") int pageSize,
            @Value("${app.account-erasure.reconciliation.recent-window:48h}") Duration recentWindow) {
        return new AccountReconciliationService(deletedUsersSource, markers, accountErasureService, Clock.systemUTC(),
                pageSize, recentWindow);
    }

    /** A comma-separated list of ids, trimmed and lowercased like the businessIds in tokens; blanks dropped. */
    static Set<String> parseIds(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(String::strip)
                .filter(id -> !id.isEmpty())
                .map(id -> id.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Bean
    public ScheduleService scheduleService(EventRepository eventRepository, EventService eventService) {
        return new ScheduleService(eventRepository, eventService, Clock.systemUTC(), USERS_ZONE);
    }

    @Bean
    public ExpenseService expenseService(EventRepository eventRepository, ExpenseRepository expenseRepository,
                                         PictureService pictureService, AccountGuard accountGuard) {
        return new ExpenseService(eventRepository, expenseRepository, pictureService, accountGuard, Clock.systemUTC());
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
