package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.DeletedUserMarker;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.persistence.entities.DeletedUserEntity;
import com.calendar.events.infrastructure.persistence.mappers.DeletedUserPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveDeletedUserMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
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
class MongoDeletedUserMarkersAdapterTest {

    private static final Instant FIRST = Instant.parse("2026-10-15T14:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-16T09:00:00Z");

    @Mock
    private ReactiveDeletedUserMongoRepository mongoRepository;

    private final DeletedUserPersistenceMapper mapper = Mappers.getMapper(DeletedUserPersistenceMapper.class);

    private MongoDeletedUserMarkersAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoDeletedUserMarkersAdapter(mongoRepository, mapper);
    }

    private static boolean storeUnavailable(Throwable error) {
        return error instanceof EventException e && e.getErrorCode() == EventErrorCode.STORE_UNAVAILABLE;
    }

    @Test
    void markDeleted_shouldInsertTheMarker_whenAbsent() {
        when(mongoRepository.insert(any(DeletedUserEntity.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(adapter.markDeleted("u1", FIRST))
                .expectNext(new DeletedUserMarker("u1", FIRST, null))
                .verifyComplete();
    }

    @Test
    void markDeleted_shouldReturnTheStoredMarker_whenAlreadyRecorded() {
        when(mongoRepository.insert(any(DeletedUserEntity.class))).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        when(mongoRepository.findById("u1")).thenReturn(Mono.just(new DeletedUserEntity("u1", FIRST, LATER)));

        StepVerifier.create(adapter.markDeleted("u1", LATER))
                .expectNext(new DeletedUserMarker("u1", FIRST, LATER))
                .verifyComplete();
    }

    @Test
    void markDeleted_shouldTranslateAStoreFailure() {
        when(mongoRepository.insert(any(DeletedUserEntity.class)))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.markDeleted("u1", FIRST))
                .expectErrorMatches(MongoDeletedUserMarkersAdapterTest::storeUnavailable)
                .verify();
    }

    @Test
    void markDeleted_shouldTranslateAFailedReadBack() {
        when(mongoRepository.insert(any(DeletedUserEntity.class))).thenReturn(Mono.error(new DuplicateKeyException("dup")));
        when(mongoRepository.findById("u1")).thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.markDeleted("u1", FIRST))
                .expectErrorMatches(MongoDeletedUserMarkersAdapterTest::storeUnavailable)
                .verify();
    }

    @Test
    void isDeleted_shouldAnswerFromTheStore() {
        when(mongoRepository.existsById("u1")).thenReturn(Mono.just(true));
        when(mongoRepository.existsById("u2")).thenReturn(Mono.just(false));

        StepVerifier.create(adapter.isDeleted("u1")).expectNext(true).verifyComplete();
        StepVerifier.create(adapter.isDeleted("u2")).expectNext(false).verifyComplete();
    }

    @Test
    void isDeleted_shouldTranslateAStoreFailure() {
        when(mongoRepository.existsById("u1")).thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.isDeleted("u1"))
                .expectErrorMatches(MongoDeletedUserMarkersAdapterTest::storeUnavailable)
                .verify();
    }

    @Test
    void deletedAmong_shouldReturnOnlyMarkedIds() {
        DeletedUserEntity marked = new DeletedUserEntity();
        marked.setId("u1");
        when(mongoRepository.findDeletedAmong(List.of("u1", "u2"))).thenReturn(Flux.just(marked));

        StepVerifier.create(adapter.deletedAmong(List.of("u1", "u2"))).expectNext(Set.of("u1")).verifyComplete();
    }

    @Test
    void deletedAmong_shouldNotQuery_whenGivenNoIds() {
        StepVerifier.create(adapter.deletedAmong(List.of())).expectNext(Set.of()).verifyComplete();
        verify(mongoRepository, never()).findDeletedAmong(any());
    }

    @Test
    void deletedAmong_shouldTranslateAStoreFailure() {
        when(mongoRepository.findDeletedAmong(List.of("u1")))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.deletedAmong(List.of("u1")))
                .expectErrorMatches(MongoDeletedUserMarkersAdapterTest::storeUnavailable)
                .verify();
    }

    @Test
    void erasedAmong_shouldReturnOnlyErasedIds() {
        DeletedUserEntity erased = new DeletedUserEntity();
        erased.setId("u1");
        when(mongoRepository.findErasedAmong(List.of("u1", "u2"))).thenReturn(Flux.just(erased));

        StepVerifier.create(adapter.erasedAmong(List.of("u1", "u2"))).expectNext(Set.of("u1")).verifyComplete();
    }

    @Test
    void erasedAmong_shouldNotQuery_whenGivenNoIds() {
        StepVerifier.create(adapter.erasedAmong(List.of())).expectNext(Set.of()).verifyComplete();
        verify(mongoRepository, never()).findErasedAmong(any());
    }

    @Test
    void erasedAmong_shouldTranslateAStoreFailure() {
        when(mongoRepository.findErasedAmong(List.of("u1")))
                .thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.erasedAmong(List.of("u1")))
                .expectErrorMatches(MongoDeletedUserMarkersAdapterTest::storeUnavailable)
                .verify();
    }

    @Test
    void markErased_shouldSetTheErasureTime() {
        when(mongoRepository.markErased("u1", LATER)).thenReturn(Mono.just(1L));

        StepVerifier.create(adapter.markErased("u1", LATER)).verifyComplete();
        verify(mongoRepository).markErased("u1", LATER);
    }

    @Test
    void markErased_shouldTranslateAStoreFailure() {
        when(mongoRepository.markErased("u1", LATER)).thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.markErased("u1", LATER))
                .expectErrorMatches(MongoDeletedUserMarkersAdapterTest::storeUnavailable)
                .verify();
    }
}
