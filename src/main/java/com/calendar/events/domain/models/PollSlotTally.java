package com.calendar.events.domain.models;

import java.util.List;

/** Who answered what for one slot. */
public record PollSlotTally(PollSlot slot, List<String> yes, List<String> maybe, List<String> no) {
}
