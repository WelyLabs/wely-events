package com.calendar.events.infrastructure.persistence.adapters;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.Friendship;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.infrastructure.persistence.entities.FriendshipEntity;
import com.calendar.events.infrastructure.persistence.mappers.FriendshipPersistenceMapper;
import com.calendar.events.infrastructure.persistence.repositories.ReactiveFriendshipMongoRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The social graph as the {@code friendships} projection holds it: every request path reads it here
 * instead of asking wely-social, so this service keeps answering while wely-social is down, a few
 * seconds behind a change at most.
 */
@Component
public class MongoSocialGraphAdapter implements SocialGraph {

    private final ReactiveFriendshipMongoRepository mongoRepository;
    private final FriendshipPersistenceMapper mapper;

    public MongoSocialGraphAdapter(ReactiveFriendshipMongoRepository mongoRepository, FriendshipPersistenceMapper mapper) {
        this.mongoRepository = mongoRepository;
        this.mapper = mapper;
    }

    @Override
    public Mono<Set<String>> friendIdsOf(String userId) {
        return mongoRepository.findFriendshipsOf(userId)
                .map(mapper::toDomain)
                .map(friendship -> friendship.otherThan(userId))
                .collect(Collectors.toSet());
    }

    /** Empty for oneself, a stranger, or a pair that parted. */
    @Override
    public Mono<Closeness> closenessBetween(String userId, String friendId) {
        if (userId.equals(friendId)) {
            return Mono.empty();
        }
        return mongoRepository.findById(Friendship.pairIdOf(userId, friendId))
                .filter(FriendshipEntity::isFriends)
                .map(mapper::toDomain)
                .map(Friendship::closeness);
    }

    @Override
    public Mono<Map<String, Closeness>> friendsOf(String userId) {
        return mongoRepository.findFriendshipsOf(userId)
                .map(mapper::toDomain)
                .collectMap(friendship -> friendship.otherThan(userId), Friendship::closeness);
    }
}
