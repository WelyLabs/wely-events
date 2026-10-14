package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An event's date poll, embedded in its document so a vote or a fix is one atomic update. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollEntity {
    /** OPEN or CLOSED. */
    @Indexed
    private String status;
    @Builder.Default
    private List<PollSlotEntity> slots = new ArrayList<>();
    /** Voter businessId → slot id → YES, MAYBE or NO. Keyed by voter so one ballot is one $set. */
    @Builder.Default
    private Map<String, Map<String, String>> ballots = new LinkedHashMap<>();
    private String fixedSlotId;
}
