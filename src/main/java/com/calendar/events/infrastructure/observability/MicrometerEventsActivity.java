package com.calendar.events.infrastructure.observability;

import com.calendar.events.domain.ports.EventsActivity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@link EventsActivity} as Micrometer counters. The names are a contract with the Grafana dashboards, which
 * read {@code wely_events_creations_total}, {@code wely_events_invitations_sent_total},
 * {@code wely_events_poll_creations_total} and {@code wely_events_polls_votes_total}. No tag carries an id.
 *
 * <p>No name ends in {@code .created}: the Prometheus client strips a {@code _created} suffix from a counter,
 * so {@code wely.events.created} would have been published as {@code wely_events_total}.
 */
@Component
public class MicrometerEventsActivity implements EventsActivity {

    static final String EVENT_CREATIONS = "wely.events.creations";
    static final String INVITATIONS_SENT = "wely.events.invitations.sent";
    static final String POLL_CREATIONS = "wely.events.poll.creations";
    static final String POLL_VOTES = "wely.events.polls.votes";

    private final Counter eventsCreated;
    private final Counter invitationsSent;
    private final Counter pollsCreated;
    private final Counter pollVotes;

    /** Registered up front, so each series exists at zero before the first fact rather than appearing later. */
    public MicrometerEventsActivity(MeterRegistry meterRegistry) {
        this.eventsCreated = Counter.builder(EVENT_CREATIONS)
                .description("Events created by users, surprises included, birthdays not")
                .register(meterRegistry);
        this.invitationsSent = Counter.builder(INVITATIONS_SENT)
                .description("Friends invited to an event by its organizer")
                .register(meterRegistry);
        this.pollsCreated = Counter.builder(POLL_CREATIONS)
                .description("Events created with a date poll")
                .register(meterRegistry);
        this.pollVotes = Counter.builder(POLL_VOTES)
                .description("Ballots cast in date polls")
                .register(meterRegistry);
    }

    @Override
    public void eventCreated() {
        eventsCreated.increment();
    }

    @Override
    public void invitationSent() {
        invitationsSent.increment();
    }

    @Override
    public void pollCreated() {
        pollsCreated.increment();
    }

    @Override
    public void pollVoted() {
        pollVotes.increment();
    }
}
