package com.calendar.events.domain.models;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A date poll: the slots the organizer proposed and everyone's ballot.
 *
 * @param slots       sorted by start; the first one is where the event sits while the poll is open
 * @param ballots     per voter (businessId), their answer per slot id
 * @param fixedSlotId the slot the organizer chose; null while open
 */
public record Poll(PollStatus status, List<PollSlot> slots, Map<String, Map<String, PollAnswer>> ballots,
                   String fixedSlotId) {

    public static final int MIN_SLOTS = 2;
    public static final int MAX_SLOTS = 5;
    private static final int SLOT_ID_LENGTH = 8;

    public Poll {
        slots = slots == null ? List.of() : List.copyOf(slots);
        // Insertion order kept, so voters are listed in the order they first voted.
        ballots = ballots == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(ballots));
    }

    /**
     * Opens a poll on 2 to 5 distinct candidates, none starting before {@code now}, each ending
     * after it starts. Each gets a short id; they are kept sorted by start.
     */
    public static Poll open(List<PollSlot> candidates, Instant now) {
        if (candidates == null || candidates.size() < MIN_SLOTS || candidates.size() > MAX_SLOTS) {
            throw invalid();
        }
        Set<String> seen = new HashSet<>();
        Set<String> ids = new HashSet<>();
        List<PollSlot> slots = new ArrayList<>();
        for (PollSlot candidate : candidates) {
            if (candidate == null || candidate.start() == null || candidate.end() == null
                    || !candidate.end().isAfter(candidate.start()) || candidate.start().isBefore(now)
                    || !seen.add(candidate.start() + "/" + candidate.end())) {
                throw invalid();
            }
            slots.add(new PollSlot(newSlotId(ids), candidate.start(), candidate.end()));
        }
        slots.sort(Comparator.comparing(PollSlot::start).thenComparing(PollSlot::end));
        return new Poll(PollStatus.OPEN, slots, Map.of(), null);
    }

    public boolean isOpen() {
        return status == PollStatus.OPEN;
    }

    public boolean hasSlot(String slotId) {
        return slot(slotId).isPresent();
    }

    public Optional<PollSlot> slot(String slotId) {
        return slots.stream().filter(slot -> slot.id().equals(slotId)).findFirst();
    }

    /** The earliest slot: where the event sits on calendars while the poll is open. */
    public PollSlot earliest() {
        return slots.getFirst();
    }

    /** Whether every answer is for one of this poll's slots. */
    public boolean acceptsBallot(Map<String, PollAnswer> answers) {
        return answers.keySet().stream().allMatch(this::hasSlot);
    }

    public List<String> yesVotersOf(String slotId) {
        return votersOf(slotId, PollAnswer.YES);
    }

    /** Per slot, in slot order, who answered yes, maybe and no. */
    public List<PollSlotTally> tally() {
        return slots.stream()
                .map(slot -> new PollSlotTally(slot,
                        votersOf(slot.id(), PollAnswer.YES),
                        votersOf(slot.id(), PollAnswer.MAYBE),
                        votersOf(slot.id(), PollAnswer.NO)))
                .toList();
    }

    private List<String> votersOf(String slotId, PollAnswer answer) {
        return ballots.entrySet().stream()
                .filter(ballot -> ballot.getValue() != null && answer == ballot.getValue().get(slotId))
                .map(Map.Entry::getKey)
                .toList();
    }

    // Short ids keep the ballots readable; 8 hex chars among at most 5 slots, redrawn on the rare clash.
    private static String newSlotId(Set<String> taken) {
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, SLOT_ID_LENGTH);
        } while (!taken.add(id));
        return id;
    }

    private static EventException invalid() {
        return new EventException(EventErrorCode.POLL_INVALID);
    }
}
