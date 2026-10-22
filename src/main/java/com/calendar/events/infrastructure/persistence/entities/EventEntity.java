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
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    /** PUBLIC, FRIENDS, PRIVATE or COMMUNITY. Absent on events stored before it existed, which count as FRIENDS. */
    private String visibility;
    /** The community the event was published in; absent for an event of one's own. */
    @Indexed(sparse = true)
    private String communityId;
    /**
     * A community event's MAYBE and NO answers, keyed by member id; a YES is being a participant.
     * Each member writes only their own field, so two answers never overwrite each other.
     */
    private Map<String, String> responses;
    /**
     * Bumped by every change to who takes part, so wely-chat can tell a newer member list from a
     * stale one. Absent on events stored before the group chat existed, which publish as 0.
     */
    private Long groupVersion;
    /**
     * Whether wely-chat has been sent the current member list. False or absent: the relay sends it.
     * Set in the same update as the change, which makes this document its own outbox.
     */
    @Indexed
    private Boolean groupSynced;
    /** Null unless the organizer proposed several dates. */
    private PollEntity poll;
    /**
     * Whether wely-chat has been told the date fixed by the poll. False: the date relay sends it.
     * Null when there is nothing to tell. Set false in the same update that fixes the date.
     */
    @Indexed
    private Boolean dateFixSynced;
    /**
     * Bumped by every date fix or move, so the date relay can tell the date it announced from a
     * newer one. Absent on events stored before moves existed, which match as null.
     */
    private Long dateFixVersion;
    /** The dates before the last move, for its announcement. Null after a poll fix, which has none. */
    private Instant rescheduledFromStart;
    private Instant rescheduledFromEnd;
    /** Whole days, midnight to midnight UTC. Absent, so false, on events stored before it existed. */
    private boolean allDay;
    /** STANDARD, BIRTHDAY or SURPRISE. Absent on events stored before kinds existed, which count as STANDARD. */
    @Indexed
    private String kind;
    /** Whose birthday a BIRTHDAY (its organizer) or a SURPRISE celebrates. */
    @Indexed(sparse = true)
    private String honoreeId;
    /** Friends invited and not answered yet; absent on events stored before invitations. */
    @Indexed(sparse = true)
    private List<String> invitedIds;

    /** Who asked to join and waits for the organizer's answer; a surprise only. */
    private List<String> requestedIds;
    /** Users every read query leaves the event out for: a surprise's honoree. */
    private List<String> hiddenFrom;
    /** A BIRTHDAY's month (1-12) and day; its dates sit in the placeholder year 2000. */
    private Integer birthMonth;
    private Integer birthDay;
    private String honoreeName;
    /** A SURPRISE's organizer as its notices name them. */
    private String organizerName;
    /** A SURPRISE's {@code honoreeId:year}: unique, so two organizers racing cannot both create one. */
    @Indexed(unique = true, sparse = true)
    private String occurrenceKey;
}
