package com.calendar.events.application.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.calendar.events.domain.models.Visibility;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventRequest {
    @NotBlank(message = "Title is required")
    private String title;
    @NotNull(message = "Date is required")
    private Instant startDate;
    private Instant endDate;
    @NotBlank(message = "Location is required")
    private String location;
    private String image;
    private String description;
    private boolean subscribeByDefault;
    /** Optional: FRIENDS when left out. */
    private Visibility visibility;
}
