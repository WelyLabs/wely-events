package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EventDetailsTest {

    @Test
    void withVisibility_shouldOnlyChangeTheVisibility() {
        EventDetails details = new EventDetails("T", "L", new GeoPoint(1, 2), "D", Visibility.PUBLIC);

        assertThat(details.withVisibility(Visibility.PRIVATE))
                .isEqualTo(new EventDetails("T", "L", new GeoPoint(1, 2), "D", Visibility.PRIVATE));
    }
}
