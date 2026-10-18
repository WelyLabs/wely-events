package com.calendar.events.domain.models;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class FriendshipChangeTypeTest {

    @Test
    void friendsAfter_shouldHoldForAnAcceptanceAClosenessChangeAndTheSnapshotOnly() {
        assertThat(Arrays.stream(FriendshipChangeType.values()).filter(FriendshipChangeType::friendsAfter))
                .containsExactlyInAnyOrder(FriendshipChangeType.ACCEPTED, FriendshipChangeType.CLOSENESS_CHANGED,
                        FriendshipChangeType.SNAPSHOT);
    }
}
