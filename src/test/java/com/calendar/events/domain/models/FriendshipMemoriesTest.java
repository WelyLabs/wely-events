package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FriendshipMemoriesTest {

    @Test
    void constructor_shouldCopyTheHighlights() {
        List<Event> highlights = new ArrayList<>(List.of(Event.builder().id("e1").build()));
        FriendshipMemories memories = new FriendshipMemories("lea", "Léa", Closeness.INTIMATE, null, 1, 400, 1,
                null, null, highlights);
        highlights.clear();

        assertThat(memories.highlights()).hasSize(1);
        assertThatThrownBy(() -> memories.highlights().add(Event.builder().build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
