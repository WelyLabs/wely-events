package com.calendar.events.infrastructure.realtime;

import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.ports.PollUpdates;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Poll updates passed in memory: one multicast sink per watched event, dropped with its last watcher.
 *
 * <p>Assumes a single replica of wely-events: a vote handled by another pod would never reach the
 * watchers here. MongoDB runs without a replica set, so change streams are not available to
 * share them; see AUDIT.md.
 */
@Component
public class InMemoryPollUpdates implements PollUpdates {

    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    @Override
    public void publish(String eventId, Poll poll) {
        Channel channel = channels.get(eventId);
        if (channel == null) {
            return;
        }
        // A sink takes one emitter at a time; two votes on one event would otherwise race.
        synchronized (channel) {
            channel.sink.tryEmitNext(poll);
        }
    }

    @Override
    public Flux<Poll> stream(String eventId) {
        // Counted at subscription, inside compute: a channel is never dropped while being joined.
        return Flux.defer(() -> {
            Channel channel = channels.compute(eventId, (id, existing) -> {
                Channel joined = existing != null ? existing : new Channel();
                joined.watchers++;
                return joined;
            });
            return channel.sink.asFlux().doFinally(signal -> leave(eventId, channel));
        });
    }

    private void leave(String eventId, Channel channel) {
        channels.computeIfPresent(eventId, (id, current) -> {
            if (current != channel) {
                return current;
            }
            current.watchers--;
            return current.watchers == 0 ? null : current;
        });
    }

    /** Package-private for the tests: how many events have a channel. */
    int channelCount() {
        return channels.size();
    }

    /** Watchers is only touched inside the map's compute, which serializes it per key. */
    private static final class Channel {
        private final Sinks.Many<Poll> sink = Sinks.many().multicast().directBestEffort();
        private int watchers;
    }
}
