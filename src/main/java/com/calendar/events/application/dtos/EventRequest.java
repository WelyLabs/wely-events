package com.calendar.events.application.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.calendar.events.domain.models.Visibility;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {
    @NotBlank(message = "Title is required")
    private String title;
    /** Required unless candidate slots are given: the event then sits on the earliest. */
    private Instant startDate;
    private Instant endDate;
    @NotBlank(message = "Location is required")
    private String location;
    /** Optional: set when the location was picked on the map. */
    @Valid
    private CoordinatesDto coordinates;
    private String description;
    /** Optional: FRIENDS when left out. Ignored when the event is published in a community. */
    private Visibility visibility;
    /** Optional: publishes the event in this community, for its members only; the caller must be one. */
    private String communityId;
    /**
     * Optional: 2 to 5 dates to vote on instead of a fixed one. The event then sits on the
     * earliest, and startDate/endDate are overridden.
     */
    private List<SlotRequest> candidateSlots;

    @JsonIgnore
    @AssertTrue(message = "Date is required")
    public boolean isDateGiven() {
        return startDate != null || candidateSlots != null;
    }
}
