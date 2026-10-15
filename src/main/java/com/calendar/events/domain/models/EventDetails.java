package com.calendar.events.domain.models;

/**
 * The part of an event its organizer may rewrite at any time: what it is, where, and who may see
 * it. The date has its own rules ({@code ScheduleService}), and so do the cover and the people.
 *
 * @param coordinates null when the place was typed rather than picked on the map
 * @param visibility  null keeps the current one
 */
public record EventDetails(String title, String location, GeoPoint coordinates, String description,
                           Visibility visibility) {

    public EventDetails withVisibility(Visibility visibility) {
        return new EventDetails(title, location, coordinates, description, visibility);
    }
}
