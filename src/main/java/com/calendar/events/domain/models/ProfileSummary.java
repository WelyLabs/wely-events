package com.calendar.events.domain.models;

import java.time.Instant;
import java.util.List;

/**
 * A member's life in Wely, for their profile page: how much they went out this year, what is next,
 * what is behind them, who they go out with, where, and where their accounts stand.
 *
 * <p>An outing is an event they organize or take part in that has started; "this year" and "this
 * month" are read in the user's time zone. The year in review adds the friends made this year, those
 * who became closer, and the friends most often out with them this year.
 */
public record ProfileSummary(
        int outingsThisYear,
        int outingsThisMonth,
        int organizedThisYear,
        int placesThisYear,
        List<Event> upcoming,
        List<Event> memories,
        int pastCount,
        List<Companion> circle,
        List<Place> places,
        List<EventBalance> balances,
        List<NewFriend> newFriendsThisYear,
        List<Upgrade> upgradesThisYear,
        List<Companion> topFriendsThisYear
) {
    public ProfileSummary {
        upcoming = List.copyOf(upcoming);
        memories = List.copyOf(memories);
        circle = List.copyOf(circle);
        places = List.copyOf(places);
        balances = List.copyOf(balances);
        newFriendsThisYear = List.copyOf(newFriendsThisYear);
        upgradesThisYear = List.copyOf(upgradesThisYear);
        topFriendsThisYear = List.copyOf(topFriendsThisYear);
    }

    /** A friend made this year, still a friend. */
    public record NewFriend(String userId, Instant since) {}

    /** A friend who became closer this year: the level reached, and when. */
    public record Upgrade(String userId, Closeness closeness, Instant at) {}

    /** Someone the member went out with, and how often. */
    public record Companion(String userId, int outingsTogether) {}

    /** A place the member went to, and how often; no coordinates when it was typed by hand. */
    public record Place(String label, GeoPoint coordinates, int visits) {}

    /** Where the member stands on one event's expenses: positive when owed, negative when owing. */
    public record EventBalance(String eventId, String title, long netCents) {}
}
