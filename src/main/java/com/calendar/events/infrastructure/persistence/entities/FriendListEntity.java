package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A user's friend list as wely-social last gave it to them. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "friend_lists")
public class FriendListEntity {
    /** The user's businessId. */
    @Id
    private String id;
    @Builder.Default
    private List<FriendEntry> friends = new ArrayList<>();
    private Instant refreshedAt;

    /** One friend; indexed so that "whose lists name this user" is one index lookup. */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FriendEntry {
        @Indexed
        private String userId;
        /** FRIEND, CLOSE or INTIMATE. */
        private String closeness;
    }
}
