package com.calendar.events.infrastructure.realtime;

import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.ports.PollUpdates;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Poll updates passed in memory, one sink per watched event, so on a single replica only (see AUDIT.md). */
@Component
public class InMemoryPollUpdates implements PollUpdates {

    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();
    /** Viewers whose streams must end, e.g. a deleted account; heard by every open stream. */
    private final Sinks.Many<String> disconnections = Sinks.many().multicast().directBestEffort();

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
    public Flux<Poll> stream(String eventId, String viewerId) {
        // Counted at subscription, inside compute: a channel is never dropped while being joined.
        return Flux.defer(() -> {
            Channel channel = channels.compute(eventId, (id, existing) -> {
                Channel joined = existing != null ? existing : new Channel();
                joined.watchers++;
                return joined;
            });
            return channel.sink.asFlux()
                    .takeUntilOther(disconnections.asFlux().filter(viewerId::equals))
                    .doFinally(signal -> leave(eventId, channel));
        });
    }

    @Override
    public void disconnect(String viewerId) {
        // One emitter at a time, as for a channel.
        synchronized (disconnections) {
            disconnections.tryEmitNext(viewerId);
        }
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
