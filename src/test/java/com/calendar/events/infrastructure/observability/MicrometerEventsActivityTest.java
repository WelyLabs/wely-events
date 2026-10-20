package com.calendar.events.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerEventsActivityTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MicrometerEventsActivity activity = new MicrometerEventsActivity(meterRegistry);

    private double count(String name) {
        return meterRegistry.get(name).counter().count();
    }

    @Test
    void constructor_shouldRegisterEveryCounterAtZero_withoutTags() {
        for (String name : new String[]{"wely.events.creations", "wely.events.invitations.sent",
                "wely.events.poll.creations", "wely.events.polls.votes"}) {
            assertThat(count(name)).isZero();
            assertThat(meterRegistry.get(name).counter().getId().getTags()).isEmpty();
        }
    }

    @Test
    void eventCreated_shouldCountAnEvent() {
        activity.eventCreated();
        activity.eventCreated();

        assertThat(count("wely.events.creations")).isEqualTo(2.0);
    }

    @Test
    void invitationSent_shouldCountAnInvitation() {
        activity.invitationSent();

        assertThat(count("wely.events.invitations.sent")).isEqualTo(1.0);
    }

    @Test
    void pollCreated_shouldCountAPoll() {
        activity.pollCreated();

        assertThat(count("wely.events.poll.creations")).isEqualTo(1.0);
    }

    @Test
    void pollVoted_shouldCountAVote() {
        activity.pollVoted();

        assertThat(count("wely.events.polls.votes")).isEqualTo(1.0);
        assertThat(count("wely.events.creations")).isZero();
    }
}
