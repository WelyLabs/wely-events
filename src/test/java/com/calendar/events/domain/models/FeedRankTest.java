package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeedRankTest {

    @Test
    void named_shouldReadAnyCase_andDefaultWithoutAName() {
        assertThat(FeedRank.named("affinity")).contains(FeedRank.AFFINITY);
        assertThat(FeedRank.named(" AFFINITY ")).contains(FeedRank.AFFINITY);
        assertThat(FeedRank.named(null)).contains(FeedRank.DEFAULT);
        assertThat(FeedRank.named("")).contains(FeedRank.DEFAULT);
    }

    @Test
    void named_shouldBeEmpty_forAnUnknownRank() {
        assertThat(FeedRank.named("popularity")).isEmpty();
    }
}
