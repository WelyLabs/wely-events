package com.calendar.events.infrastructure.persistence.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** An account wely-users deleted, kept forever with only its pseudonymous id. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "deleted_users")
public class DeletedUserEntity {
    /** The businessId: one marker per account, looked up by _id only. */
    @Id
    private String id;
    private Instant deletedAt;
    /** Null until an erasure has completed. */
    private Instant erasedAt;
}
