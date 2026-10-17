package com.calendar.events.domain.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    /** How long an event without an end is taken to last, as the calendar displays it. */
    public static final Duration DEFAULT_DURATION = Duration.ofHours(1);

    private String id;
    private String title;
    private String organizerId;
    private Instant startDate;
    private Instant endDate;
    private String location;
    /** Null when the location was typed rather than picked on the map. */
    private GeoPoint coordinates;
    private String image;
    private String description;
    @Builder.Default
    private List<String> participantIds = new ArrayList<>();
    /** Null for events stored before visibility existed; read through {@link #effectiveVisibility()}. */
    private Visibility visibility;
    /** Null unless the organizer proposed several dates instead of one. */
    private Poll poll;
    /** Read from storage only, for the deletion's version; null on events stored before group chats. */
    private Long groupVersion;
    /** The community the event was published in; null for an event of one's own. */
    private String communityId;
    /**
     * The MAYBE and NO answers of a community event's members. A YES is not kept here: it is being
     * among the participants, which every other path (joining, leaving, a poll's fix) already keeps
     * up to date. Read from storage only, written by {@code EventRepository#respond}.
     */
    @Builder.Default
    private Map<String, PollAnswer> responses = new LinkedHashMap<>();
    /** Whole days: start at 00:00Z of the first day, end at 00:00Z of the day after the last. */
    private boolean allDay;
    /** Null for events stored before kinds existed; read through {@link #effectiveKind()}. */
    private EventKind kind;
    /** Whose birthday a BIRTHDAY or a SURPRISE celebrates; null on a standard event. */
    private String honoreeId;
    /** Users every query keeps the event from, as if it did not exist: a surprise's honoree. */
    @Builder.Default
    private List<String> hiddenFrom = new ArrayList<>();
    /** A BIRTHDAY's day and month; its dates are a placeholder year, never the year of birth. */
    private Integer birthMonth;
    private Integer birthDay;
    /** The name a BIRTHDAY's or a SURPRISE's notices and occurrences show. */
    private String honoreeName;
    /** A SURPRISE's organizer as named in its notices, from the creator's token. */
    private String organizerName;
    /** A SURPRISE's {@code honoreeId:year} of the birthday it celebrates: unique, one surprise per birthday. */
    private String occurrenceKey;

    /** When the event stops keeping its people busy. */
    public Instant effectiveEnd() {
        return endDate != null ? endDate : startDate.plus(DEFAULT_DURATION);
    }

    /** Whether the date is still being voted on: then startDate/endDate are only the earliest proposal. */
    public boolean isPolling() {
        return poll != null && poll.isOpen();
    }

    public EventKind effectiveKind() {
        return kind != null ? kind : EventKind.STANDARD;
    }

    public boolean isBirthday() {
        return effectiveKind() == EventKind.BIRTHDAY;
    }

    public boolean isSurprise() {
        return effectiveKind() == EventKind.SURPRISE;
    }

    public boolean isHiddenFrom(String userId) {
        return hiddenFrom != null && hiddenFrom.contains(userId);
    }

    /** A BIRTHDAY's day and month; null on any other event. */
    public MonthDay birthMonthDay() {
        return birthMonth == null || birthDay == null ? null : MonthDay.of(birthMonth, birthDay);
    }

    /** The year of the birthday nearest to the event's start, by UTC date: the birthday a surprise celebrates. */
    public static int nearestOccurrenceYear(MonthDay birthday, Instant start) {
        LocalDate day = LocalDate.ofInstant(start, ZoneOffset.UTC);
        int best = day.getYear();
        long bestGap = Long.MAX_VALUE;
        for (int year = day.getYear() - 1; year <= day.getYear() + 1; year++) {
            long gap = Math.abs(ChronoUnit.DAYS.between(day, birthday.atYear(year)));
            if (gap < bestGap) {
                bestGap = gap;
                best = year;
            }
        }
        return best;
    }

    public static String occurrenceKeyOf(String honoreeId, int year) {
        return honoreeId + ":" + year;
    }

    public boolean isCommunityEvent() {
        return communityId != null;
    }

    public Visibility effectiveVisibility() {
        return visibility != null ? visibility : Visibility.DEFAULT;
    }

    /** Whether the user organizes the event or takes part in it. */
    public boolean involves(String userId) {
        return userId.equals(organizerId) || participantIds.contains(userId);
    }

    /** Whether the event ended strictly before {@code at}; an event whose poll is open is never over. */
    public boolean isOverAt(Instant at) {
        return !isPolling() && effectiveEnd().isBefore(at);
    }

    /** The earliest participant, in join order, who is neither the organizer nor one of {@code excludedIds}. */
    public Optional<String> successorExcluding(Collection<String> excludedIds) {
        return participantIds.stream()
                .filter(id -> !id.equals(organizerId) && !excludedIds.contains(id))
                .findFirst();
    }

    /**
     * Whether the user may see this event, given who the user's friends are. Never whoever it is
     * hidden from; those taking part always can; a surprise, the honoree's friends; otherwise it
     * depends on the visibility.
     */
    public boolean isVisibleTo(String userId, Set<String> friendIdsOfUser) {
        if (isHiddenFrom(userId)) {
            return false;
        }
        if (involves(userId)) {
            return true;
        }
        if (isSurprise()) {
            // The honoree's friends, whoever organizes it.
            return friendIdsOfUser.contains(honoreeId);
        }
        return switch (effectiveVisibility()) {
            case PUBLIC -> true;
            case FRIENDS -> friendIdsOfUser.contains(organizerId);
            // Membership is wely-social's to say: whoever knows it decides, see EventService.
            case PRIVATE, COMMUNITY -> false;
        };
    }
}
