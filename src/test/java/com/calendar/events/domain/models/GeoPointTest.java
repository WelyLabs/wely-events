package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoPointTest {

    @Test
    void constructor_shouldAcceptTheBoundsOfTheGlobe() {
        assertThat(new GeoPoint(-90, -180)).isEqualTo(new GeoPoint(-90, -180));
        assertThat(new GeoPoint(90, 180).latitude()).isEqualTo(90);
    }

    @ParameterizedTest
    @CsvSource({"90.1, 0, latitude", "-90.1, 0, latitude", "0, 180.1, longitude", "0, -180.1, longitude"})
    void constructor_shouldRejectAPointOffTheGlobe(double latitude, double longitude, String axis) {
        assertThatThrownBy(() -> new GeoPoint(latitude, longitude))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(axis);
    }
}
