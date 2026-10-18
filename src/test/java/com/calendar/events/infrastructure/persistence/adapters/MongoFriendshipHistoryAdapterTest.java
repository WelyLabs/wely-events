package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendshipChangeType;
import com.calendar.events.domain.models.FriendshipHistoryEntry;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipHistoryPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipHistoryMongoRepository;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoFriendshipHistoryAdapterTest {

    private static final Instant AT = Instant.parse("2026-10-18T08:00:00Z");
    private static final FriendshipHistoryEntry ENTRY = new FriendshipHistoryEntry("alice:bob", "alice", "bob",
            FriendshipChangeType.ACCEPTED, Closeness.FRIEND, null, AT, 1);

    @Mock
    private ReactiveFriendshipHistoryMongoRepository mongoRepository;

    private final FriendshipHistoryPersistenceMapper mapper = Mappers.getMapper(FriendshipHistoryPersistenceMapper.class);
    private MongoFriendshipHistoryAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoFriendshipHistoryAdapter(mongoRepository, mapper);
    }

    @Test
    void append_shouldInsertTheEntry() {
        when(mongoRepository.insert(any(com.calendar.events.infrastructure.persistence.entities.FriendshipHistoryEntity.class)))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(adapter.append(ENTRY)).expectNext(true).verifyComplete();
    }

    @Test
    void append_shouldSayFalse_whenThePairsVersionIsThereAlready() {
        when(mongoRepository.insert(any(com.calendar.events.infrastructure.persistence.entities.FriendshipHistoryEntity.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("dup")));

        StepVerifier.create(adapter.append(ENTRY)).expectNext(false).verifyComplete();
    }

    @Test
    void append_shouldPassAnyOtherFailureOn() {
        when(mongoRepository.insert(any(com.calendar.events.infrastructure.persistence.entities.FriendshipHistoryEntity.class)))
                .thenReturn(Mono.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.append(ENTRY)).expectError(DataAccessResourceFailureException.class).verify();
    }

    @Test
    void involvingSince_shouldReadTheEntriesBack() {
        when(mongoRepository.findInvolvingSince("alice", AT)).thenReturn(Flux.just(mapper.toEntity(ENTRY)));

        StepVerifier.create(adapter.involvingSince("alice", AT)).expectNext(ENTRY).verifyComplete();
    }

    @Test
    void forget_shouldDeleteEveryEntryInvolvingTheUser() {
        when(mongoRepository.deleteInvolving("alice")).thenReturn(Mono.just(3L));

        StepVerifier.create(adapter.forget("alice")).expectNext(3L).verifyComplete();
    }
}
