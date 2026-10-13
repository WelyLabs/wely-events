package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EventCoverTest {

    private final SignedUrls urls = new SignedUrls(Map.of("events/1/cover-a.jpg", "https://media/signed"));

    @Test
    void of_shouldReadTheEventsImage() {
        assertThat(EventCover.of(Event.builder().image("events/1/cover-a.jpg").build()))
                .isEqualTo(new EventCover("events/1/cover-a.jpg"));
    }

    @Test
    void newKey_shouldBeUniqueUnderTheEventsPrefix() {
        String first = EventCover.newKey("1");

        assertThat(first).matches("events/1/cover-[0-9a-f-]{36}\\.jpg");
        assertThat(EventCover.newKey("1")).isNotEqualTo(first);
    }

    @Test
    void isStored_shouldBeTrue_onlyForAKeyUnderThePrefix() {
        assertThat(new EventCover("events/1/cover-a.jpg").isStored()).isTrue();
        assertThat(new EventCover("/demo/events/picnic.jpg").isStored()).isFalse();
        assertThat(new EventCover(null).isStored()).isFalse();
    }

    @Test
    void storedKey_shouldBeNull_whenNothingIsStored() {
        assertThat(new EventCover("events/1/cover-a.jpg").storedKey()).isEqualTo("events/1/cover-a.jpg");
        assertThat(new EventCover("/demo/events/picnic.jpg").storedKey()).isNull();
    }

    @Test
    void shownWith_shouldGiveTheSignedUrl_forAStoredCover() {
        assertThat(new EventCover("events/1/cover-a.jpg").shownWith(urls)).isEqualTo("https://media/signed");
    }

    @Test
    void shownWith_shouldGiveNull_forAStoredCoverWithoutUrl() {
        assertThat(new EventCover("events/2/cover-b.jpg").shownWith(urls)).isNull();
        assertThat(new EventCover("events/1/cover-a.jpg").shownWith(SignedUrls.NONE)).isNull();
    }

    @Test
    void shownWith_shouldPassAPathTheWebAppServesThrough() {
        assertThat(new EventCover("/demo/events/picnic.jpg").shownWith(urls)).isEqualTo("/demo/events/picnic.jpg");
        assertThat(new EventCover(null).shownWith(urls)).isNull();
    }
}
