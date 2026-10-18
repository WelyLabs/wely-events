package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.domain.ports.FriendshipDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipServiceTest {

    private static final Friendship FRIENDS =
            new Friendship("alice", "bob", true, Closeness.CLOSE, Instant.parse("2026-05-01T10:00:00Z"), 7);

    @Mock
    private FriendshipDirectory directory;
    @Mock
    private DeletedUserMarkers markers;

    private FriendshipService service;

    @BeforeEach
    void setUp() {
        service = new FriendshipService(directory, new AccountGuard(markers));
    }

    @Test
    void record_shouldStoreTheChange_andSayWhetherItWasNewer() {
        when(markers.deletedAmong(List.of("alice", "bob"))).thenReturn(Mono.just(Set.of()));
        when(directory.record(FRIENDS)).thenReturn(Mono.just(true), Mono.just(false));

        StepVerifier.create(service.record(FRIENDS)).expectNext(true).verifyComplete();
        StepVerifier.create(service.record(FRIENDS)).expectNext(false).verifyComplete();
    }

    @Test
    void record_shouldIgnoreAPairNamingADeletedAccount() {
        when(markers.deletedAmong(List.of("alice", "bob"))).thenReturn(Mono.just(Set.of("bob")));

        StepVerifier.create(service.record(FRIENDS)).expectNext(false).verifyComplete();
        verify(directory, never()).record(any());
    }

    @Test
    void record_shouldFail_whenTheRegisterCannotBeRead() {
        when(markers.deletedAmong(List.of("alice", "bob"))).thenReturn(Mono.error(new IllegalStateException("mongo down")));

        StepVerifier.create(service.record(FRIENDS)).expectError(IllegalStateException.class).verify();
        verify(directory, never()).record(any());
    }
}
