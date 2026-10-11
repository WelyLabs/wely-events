package com.calendar.events.domain.models;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;

/**
 * The area a map shows, in decimal degrees.
 *
 * <p>At most a quarter of the globe each way: MongoDB reads a polygon wider than a hemisphere
 * as its complement, and nobody looks for an evening out across a continent anyway.
 */
public record GeoBox(double south, double west, double north, double east) {

    public static final double MAX_SPAN_DEGREES = 90;

    public GeoBox {
        boolean onTheGlobe = south >= -90 && north <= 90 && west >= -180 && east <= 180;
        boolean ordered = south < north && west < east;
        boolean small = north - south <= MAX_SPAN_DEGREES && east - west <= MAX_SPAN_DEGREES;
        if (!onTheGlobe || !ordered || !small) {
            throw new EventException(EventErrorCode.INVALID_MAP_AREA);
        }
    }
}
