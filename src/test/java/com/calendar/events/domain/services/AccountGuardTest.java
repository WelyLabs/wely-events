package com.calendar.events.domain.services;

import com.calendar.events.domain.ports.DeletedUserMarkers;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountGuardTest {

    @Mock
    private DeletedUserMarkers markers;

    private AccountGuard guard;

    @BeforeEach
    void setUp() {
        guard = new AccountGuard(markers);
    }

    @Test
    void isDeleted_shouldBeTrue_whenAMarkerExists() {
        when(markers.isDeleted("gone")).thenReturn(Mono.just(true));

        StepVerifier.create(guard.isDeleted("gone")).expectNext(true).verifyComplete();
    }

    @Test
    void isDeleted_shouldBeFalse_whenNoMarkerExists() {
        when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

        StepVerifier.create(guard.isDeleted("lea")).expectNext(false).verifyComplete();
    }

    @Test
    void requireLive_shouldRefuseADeletedAccount() {
        when(markers.isDeleted("gone")).thenReturn(Mono.just(true));

        StepVerifier.create(guard.requireLive("gone"))
                .expectErrorMatches(error -> error instanceof EventException e
                        && e.getErrorCode() == EventErrorCode.ACCOUNT_DELETED)
                .verify();
    }

    @Test
    void requireLive_shouldCompleteEmpty_forALiveAccount() {
        when(markers.isDeleted("lea")).thenReturn(Mono.just(false));

        StepVerifier.create(guard.requireLive("lea")).verifyComplete();
    }

    @Test
    void requireLive_shouldPassOnAStoreFailure() {
        when(markers.isDeleted("lea")).thenReturn(Mono.error(new EventException(EventErrorCode.STORE_UNAVAILABLE)));

        StepVerifier.create(guard.requireLive("lea"))
                .expectErrorMatches(error -> error instanceof EventException e
                        && e.getErrorCode() == EventErrorCode.STORE_UNAVAILABLE)
                .verify();
    }

    @Test
    void deletedAmong_shouldAskTheRegister() {
        when(markers.deletedAmong(List.of("lea", "gone"))).thenReturn(Mono.just(Set.of("gone")));

        StepVerifier.create(guard.deletedAmong(List.of("lea", "gone"))).expectNext(Set.of("gone")).verifyComplete();
    }

    @Test
    void deletedAmong_shouldNotLookUp_whenGivenNoIds() {
        StepVerifier.create(guard.deletedAmong(List.of())).expectNext(Set.of()).verifyComplete();
        verify(markers, never()).deletedAmong(any());
    }
}
