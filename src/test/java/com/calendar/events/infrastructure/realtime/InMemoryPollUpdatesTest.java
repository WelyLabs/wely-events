package com.calendar.events.infrastructure.realtime;

import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollStatus;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryPollUpdatesTest {

    private static final Poll FIRST = new Poll(PollStatus.OPEN, List.of(), null, null);
    private static final Poll SECOND = new Poll(PollStatus.CLOSED, List.of(), null, "s1");

    private final InMemoryPollUpdates updates = new InMemoryPollUpdates();

    @Test
    void publish_shouldReachEveryWatcherOfThatEvent_only() {
        List<Poll> alice = new CopyOnWriteArrayList<>();
        List<Poll> bob = new CopyOnWriteArrayList<>();
        List<Poll> otherEvent = new CopyOnWriteArrayList<>();
        Disposable a = updates.stream("e1").subscribe(alice::add);
        Disposable b = updates.stream("e1").subscribe(bob::add);
        Disposable c = updates.stream("e2").subscribe(otherEvent::add);

        updates.publish("e1", FIRST);
        updates.publish("e1", SECOND);

        assertThat(alice).containsExactly(FIRST, SECOND);
        assertThat(bob).containsExactly(FIRST, SECOND);
        assertThat(otherEvent).isEmpty();
        a.dispose();
        b.dispose();
        c.dispose();
    }

    @Test
    void publish_shouldBeHarmless_whenNobodyWatches() {
        updates.publish("e1", FIRST);

        assertThat(updates.channelCount()).isZero();
    }

    @Test
    void stream_shouldNotReplayWhatWasPublishedBeforeSubscribing() {
        Disposable first = updates.stream("e1").subscribe();
        updates.publish("e1", FIRST);

        StepVerifier.create(updates.stream("e1"))
                .then(() -> updates.publish("e1", SECOND))
                .expectNext(SECOND)
                .thenCancel()
                .verify();
        first.dispose();
    }

    @Test
    void stream_shouldDropTheChannel_whenTheLastWatcherLeaves() {
        Disposable a = updates.stream("e1").subscribe();
        Disposable b = updates.stream("e1").subscribe();
        assertThat(updates.channelCount()).isEqualTo(1);

        a.dispose();
        assertThat(updates.channelCount()).isEqualTo(1);

        b.dispose();
        assertThat(updates.channelCount()).isZero();
    }

    @Test
    void stream_shouldOpenANewChannel_afterTheOldOneWasDropped() {
        updates.stream("e1").subscribe().dispose();
        List<Poll> received = new CopyOnWriteArrayList<>();

        Disposable again = updates.stream("e1").subscribe(received::add);
        updates.publish("e1", FIRST);

        assertThat(received).containsExactly(FIRST);
        again.dispose();
        assertThat(updates.channelCount()).isZero();
    }

    @Test
    void stream_shouldNotOpenAChannel_untilSubscribed() {
        updates.stream("e1");

        assertThat(updates.channelCount()).isZero();
    }
}
