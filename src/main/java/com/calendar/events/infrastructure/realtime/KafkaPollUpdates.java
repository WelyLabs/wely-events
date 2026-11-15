package com.calendar.events.infrastructure.realtime;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.ports.EventRepository;
import com.calendar.events.domain.ports.PollUpdates;
import com.calendar.events.infrastructure.realtime.models.PollUpdateBroadcastDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Poll updates fanned out to every instance through the broker.
 *
 * <p>Viewers of one event may be connected to any pod. Each change is announced on a topic every pod
 * reads in a group of its own ({@link PollUpdatesConsumer}); a pod with viewers on that event reads the
 * poll again and hands it to them. Same pattern as wely-chat's {@code KafkaMessageBroadcaster}, except
 * that the record carries the event id only: the database, not the record, decides the latest state.
 *
 * <p>Best effort: the vote is stored before it is announced, and a lost record costs a refresh.
 * {@code StreamBridge.send} blocks while the broker is unreachable, so sends run on a thread of their own.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.poll-updates.mode", havingValue = "kafka", matchIfMissing = true)
public class KafkaPollUpdates implements PollUpdates {

    static final String DESTINATION = "pollUpdates-out-0";

    private final StreamBridge streamBridge;
    private final EventRepository events;
    /** This pod's own streams; fed only by records coming back from the broker. */
    private final InMemoryPollUpdates local = new InMemoryPollUpdates();
    private final Scheduler sender = Schedulers.newSingle("poll-updates", true);

    public KafkaPollUpdates(StreamBridge streamBridge, EventRepository events) {
        this.streamBridge = streamBridge;
        this.events = events;
    }

    /** The poll given is not sent: every pod reads the stored one. */
    @Override
    public void publish(String eventId, Poll poll) {
        send(PollUpdateBroadcastDTO.changed(eventId));
    }

    @Override
    public Flux<Poll> stream(String eventId, String viewerId) {
        return local.stream(eventId, viewerId);
    }

    /** Broadcast too: the viewer's streams may be open on any pod. */
    @Override
    public void disconnect(String viewerId) {
        send(PollUpdateBroadcastDTO.disconnected(viewerId));
    }

    /** Called by {@link PollUpdatesConsumer} for each record read from the topic. */
    Mono<Void> accept(PollUpdateBroadcastDTO record) {
        if (PollUpdateBroadcastDTO.DISCONNECTED.equals(record.kind()) && record.viewerId() != null) {
            return Mono.fromRunnable(() -> local.disconnect(record.viewerId()));
        }
        if (!PollUpdateBroadcastDTO.CHANGED.equals(record.kind()) || record.eventId() == null) {
            log.warn("Poll update of kind {} dropped", record.kind());
            return Mono.empty();
        }
        // Most pods have nobody looking at this event: they skip the read.
        if (!local.isWatched(record.eventId())) {
            return Mono.empty();
        }
        return events.findById(record.eventId())
                .mapNotNull(Event::getPoll)
                .doOnNext(poll -> local.publish(record.eventId(), poll))
                .then();
    }

    private void send(PollUpdateBroadcastDTO record) {
        sender.schedule(() -> {
            try {
                if (!streamBridge.send(DESTINATION, record)) {
                    log.error("Could not broadcast a {} poll update", record.kind());
                }
            } catch (RuntimeException e) {
                log.error("Could not broadcast a {} poll update: {}", record.kind(), e.getMessage());
            }
        });
    }
}
