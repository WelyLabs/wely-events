package com.calendar.events.domain.models;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoBoxTest {

    @Test
    void constructor_shouldAcceptACityOnScreen() {
        assertThat(new GeoBox(45.70, 4.77, 45.81, 4.92).north()).isEqualTo(45.81);
    }

    @Test
    void constructor_shouldAcceptAQuarterOfTheGlobe() {
        assertThat(new GeoBox(0, 0, 90, 90)).isNotNull();
    }

    @ParameterizedTest(name = "{4}")
    @CsvSource({
            "45.8, 4.7, 45.7, 4.9, south after north",
            "45.7, 4.9, 45.8, 4.7, west after east",
            "-91, 4.7, 45.8, 4.9, off the globe southwards",
            "45.7, 4.7, 45.8, 181, off the globe eastwards",
            "-10, -60, 10, 40, wider than a quarter of the globe",
            "-60, 0, 40, 10, taller than a quarter of the globe"
    })
    void constructor_shouldRejectAnAreaMongoCannotSearch(double south, double west, double north, double east, String why) {
        assertThatThrownBy(() -> new GeoBox(south, west, north, east))
                .isInstanceOfSatisfying(EventException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(EventErrorCode.INVALID_MAP_AREA));
    }
}
