package com.calendar.events.infrastructure.realtime;

import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollStatus;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
        Disposable a = updates.stream("e1", "alice").subscribe(alice::add);
        Disposable b = updates.stream("e1", "bob").subscribe(bob::add);
        Disposable c = updates.stream("e2", "carol").subscribe(otherEvent::add);

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
        Disposable first = updates.stream("e1", "lea").subscribe();
        updates.publish("e1", FIRST);

        StepVerifier.create(updates.stream("e1", "lea"))
                .then(() -> updates.publish("e1", SECOND))
                .expectNext(SECOND)
                .thenCancel()
                .verify(Duration.ofSeconds(10));
        first.dispose();
    }

    @Test
    void stream_shouldDropTheChannel_whenTheLastWatcherLeaves() {
        Disposable a = updates.stream("e1", "lea").subscribe();
        Disposable b = updates.stream("e1", "lea").subscribe();
        assertThat(updates.channelCount()).isEqualTo(1);

        a.dispose();
        assertThat(updates.channelCount()).isEqualTo(1);

        b.dispose();
        assertThat(updates.channelCount()).isZero();
    }

    @Test
    void stream_shouldOpenANewChannel_afterTheOldOneWasDropped() {
        updates.stream("e1", "lea").subscribe().dispose();
        List<Poll> received = new CopyOnWriteArrayList<>();

        Disposable again = updates.stream("e1", "lea").subscribe(received::add);
        updates.publish("e1", FIRST);

        assertThat(received).containsExactly(FIRST);
        again.dispose();
        assertThat(updates.channelCount()).isZero();
    }

    @Test
    void disconnect_shouldCompleteEveryStreamOfThatViewer_only() {
        List<Poll> others = new CopyOnWriteArrayList<>();
        AtomicInteger completed = new AtomicInteger();
        updates.stream("e1", "gone").subscribe(poll -> { }, error -> { }, completed::incrementAndGet);
        updates.stream("e2", "gone").subscribe(poll -> { }, error -> { }, completed::incrementAndGet);
        Disposable other = updates.stream("e1", "lea").subscribe(others::add);

        updates.disconnect("gone");

        assertThat(completed).hasValue(2);
        assertThat(other.isDisposed()).isFalse();
        updates.publish("e1", FIRST);
        assertThat(others).containsExactly(FIRST);
        other.dispose();
    }

    @Test
    void disconnect_shouldBeHarmless_whenTheViewerWatchesNothing() {
        Disposable other = updates.stream("e1", "lea").subscribe();

        updates.disconnect("gone");

        assertThat(other.isDisposed()).isFalse();
        assertThat(updates.channelCount()).isEqualTo(1);
        other.dispose();
    }

    @Test
    void stream_shouldLeaveTheChannel_whenTheViewerIsDisconnected() {
        Disposable gone = updates.stream("e1", "gone").subscribe();
        assertThat(updates.channelCount()).isEqualTo(1);

        updates.disconnect("gone");

        assertThat(gone.isDisposed()).isTrue();
        assertThat(updates.channelCount()).isZero();
    }

    @Test
    void stream_shouldNotOpenAChannel_untilSubscribed() {
        updates.stream("e1", "lea");

        assertThat(updates.channelCount()).isZero();
    }
}
