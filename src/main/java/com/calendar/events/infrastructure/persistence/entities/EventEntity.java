package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "events")
// For the free-slot search: whose events, then when.
@CompoundIndexes({
        @CompoundIndex(name = "participant_start", def = "{'participantIds': 1, 'startDate': 1}"),
        @CompoundIndex(name = "organizer_start", def = "{'organizerId': 1, 'startDate': 1}")
})
public class EventEntity {
    @Id
    private String id;
    private String title;
    private String organizerId;
    private Instant startDate;
    private Instant endDate;
    private String location;
    /** GeoJSON, indexed 2dsphere for the map's area queries. */
    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint coordinates;
    private String image;
    private String description;
    @Builder.Default
    private List<String> participantIds = new ArrayList<>();
    /** PUBLIC, FRIENDS or PRIVATE. Absent on events stored before it existed, which count as FRIENDS. */
    private String visibility;
}
