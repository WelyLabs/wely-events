package com.calendar.events.domain.models;

/** A place on Earth, in decimal degrees (WGS 84, as OpenStreetMap and GPS use). */
public record GeoPoint(double latitude, double longitude) {

    public GeoPoint {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("latitude must be within [-90, 90]");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("longitude must be within [-180, 180]");
        }
    }
}
