package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoSocialGraphAdapterTest {

    private static final Instant SINCE = Instant.parse("2026-05-01T10:00:00Z");

    @Mock
    private ReactiveFriendshipMongoRepository mongoRepository;

    private final FriendshipPersistenceMapper mapper = Mappers.getMapper(FriendshipPersistenceMapper.class);
    private MongoSocialGraphAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoSocialGraphAdapter(mongoRepository, mapper);
    }

    @Test
    void friendIdsOf_shouldNameTheOtherSideOfEachPair() {
        when(mongoRepository.findFriendshipsOf("bob")).thenReturn(Flux.just(
                mapper.toEntity(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 1)),
                mapper.toEntity(new Friendship("bob", "zoe", true, Closeness.FRIEND, SINCE, 1))));

        StepVerifier.create(adapter.friendIdsOf("bob")).expectNext(Set.of("alice", "zoe")).verifyComplete();
    }

    @Test
    void friendIdsOf_shouldPassAReadFailureOn() {
        when(mongoRepository.findFriendshipsOf("bob")).thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.friendIdsOf("bob")).expectError(DataAccessResourceFailureException.class).verify();
    }

    @Test
    void closenessBetween_shouldReadThePairInEitherOrder() {
        when(mongoRepository.findById("alice:bob"))
                .thenReturn(Mono.just(mapper.toEntity(new Friendship("alice", "bob", true, Closeness.INTIMATE, SINCE, 3))));

        StepVerifier.create(adapter.closenessBetween("bob", "alice")).expectNext(Closeness.INTIMATE).verifyComplete();
    }

    @Test
    void closenessBetween_shouldBeEmpty_whenThePairPartedOrIsUnknown() {
        when(mongoRepository.findById("alice:bob"))
                .thenReturn(Mono.just(mapper.toEntity(new Friendship("alice", "bob", false, null, null, 4))));
        when(mongoRepository.findById("alice:zoe")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.closenessBetween("alice", "bob")).verifyComplete();
        StepVerifier.create(adapter.closenessBetween("zoe", "alice")).verifyComplete();
    }

    @Test
    void closenessBetween_shouldBeEmptyForOneself_withoutReading() {
        StepVerifier.create(adapter.closenessBetween("alice", "alice")).verifyComplete();

        verify(mongoRepository, never()).findById(anyString());
    }

    @Test
    void friendsOf_shouldMapEachFriendToTheirCloseness() {
        when(mongoRepository.findFriendshipsOf("bob")).thenReturn(Flux.just(
                mapper.toEntity(new Friendship("alice", "bob", true, Closeness.CLOSE, SINCE, 1)),
                mapper.toEntity(new Friendship("bob", "zoe", true, Closeness.FRIEND, SINCE, 1))));

        StepVerifier.create(adapter.friendsOf("bob"))
                .expectNext(Map.of("alice", Closeness.CLOSE, "zoe", Closeness.FRIEND))
                .verifyComplete();
    }

    @Test
    void friendsOf_shouldPassAReadFailureOn() {
        when(mongoRepository.findFriendshipsOf("bob")).thenReturn(Flux.error(new DataAccessResourceFailureException("down")));

        StepVerifier.create(adapter.friendsOf("bob")).expectError(DataAccessResourceFailureException.class).verify();
    }
}
