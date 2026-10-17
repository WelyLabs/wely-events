package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendList;
import com.calendar.events.infrastructure.persistence.entities.FriendListEntity;
import com.calendar.events.infrastructure.persistence.mappers.FriendListPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendListMongoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoFriendListsAdapterTest {

    private static final Instant NOW = Instant.parse("2027-02-19T07:00:00Z");
    private static final FriendList LEAS = new FriendList("lea", Map.of("bob", Closeness.CLOSE), NOW);

    @Mock
    private ReactiveFriendListMongoRepository mongoRepository;

    private final FriendListPersistenceMapper mapper = Mappers.getMapper(FriendListPersistenceMapper.class);
    private MongoFriendListsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MongoFriendListsAdapter(mongoRepository, mapper);
    }

    @Test
    void save_shouldReplaceTheUsersList() {
        when(mongoRepository.save(argThat((FriendListEntity entity) -> "lea".equals(entity.getId())
                && entity.getFriends().size() == 1 && "bob".equals(entity.getFriends().getFirst().getUserId()))))
                .thenAnswer(call -> Mono.just(call.getArgument(0)));

        StepVerifier.create(adapter.save(LEAS)).verifyComplete();
    }

    @Test
    void findOf_shouldMapTheUsersList() {
        when(mongoRepository.findById("lea")).thenReturn(Mono.just(mapper.toEntity(LEAS)));

        StepVerifier.create(adapter.findOf("lea")).expectNext(LEAS).verifyComplete();
    }

    @Test
    void findNaming_shouldMapTheListsNamingTheUser() {
        FriendList bobs = new FriendList("bob", Map.of("lea", Closeness.CLOSE), NOW);
        when(mongoRepository.findNaming("lea")).thenReturn(Flux.just(mapper.toEntity(bobs)));

        StepVerifier.create(adapter.findNaming("lea")).expectNext(bobs).verifyComplete();
    }

    @Test
    void delete_shouldDropTheUsersList() {
        when(mongoRepository.deleteById("lea")).thenReturn(Mono.empty());

        StepVerifier.create(adapter.delete("lea")).verifyComplete();
        verify(mongoRepository).deleteById("lea");
    }
}
