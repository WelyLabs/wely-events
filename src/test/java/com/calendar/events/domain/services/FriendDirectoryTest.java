package com.calendar.events.domain.services;

import com.calendar.events.domain.models.Closeness;
import com.calendar.events.domain.models.FriendList;
import com.calendar.events.domain.ports.FriendLists;
import com.calendar.events.domain.ports.SocialGraph;
import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendDirectoryTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @Mock
    private SocialGraph socialGraph;
    @Mock
    private FriendLists friendLists;

    private FriendDirectory directory;

    @BeforeEach
    void setUp() {
        directory = new FriendDirectory(socialGraph, friendLists, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void refreshFriendsOf_shouldAnswerWhatSocialSays_andRememberIt() {
        Map<String, Closeness> friends = Map.of("bob", Closeness.CLOSE);
        when(socialGraph.friendsOf("lea")).thenReturn(Mono.just(friends));
        when(friendLists.save(new FriendList("lea", friends, NOW))).thenReturn(Mono.empty());

        StepVerifier.create(directory.refreshFriendsOf("lea")).expectNext(friends).verifyComplete();
        verify(friendLists).save(new FriendList("lea", friends, NOW));
    }

    @Test
    void refreshFriendsOf_shouldStillAnswer_whenTheListCannotBeRemembered() {
        when(socialGraph.friendsOf("lea")).thenReturn(Mono.just(Map.of("bob", Closeness.FRIEND)));
        when(friendLists.save(any())).thenReturn(Mono.error(new DataAccessResourceFailureException("mongo down")));

        StepVerifier.create(directory.refreshFriendsOf("lea")).expectNext(Map.of("bob", Closeness.FRIEND)).verifyComplete();
    }

    @Test
    void refreshFriendsOf_shouldPassASocialOutageOn_withoutForgettingTheKnownList() {
        when(socialGraph.friendsOf("lea")).thenReturn(Mono.error(new EventException(EventErrorCode.SOCIAL_UNAVAILABLE)));

        StepVerifier.create(directory.refreshFriendsOf("lea"))
                .expectErrorMatches(e -> e instanceof EventException ex && ex.getErrorCode() == EventErrorCode.SOCIAL_UNAVAILABLE)
                .verify();
        verify(friendLists, never()).save(any());
    }

    @Test
    void knownFriendsOf_shouldMergeTheUsersListWithThoseNamingThem() {
        when(friendLists.findOf("lea")).thenReturn(Mono.just(new FriendList("lea", Map.of("bob", Closeness.CLOSE), NOW)));
        when(friendLists.findNaming("lea")).thenReturn(Flux.just(new FriendList("hugo", Map.of("lea", Closeness.INTIMATE), NOW.plusSeconds(1))));

        StepVerifier.create(directory.knownFriendsOf("lea"))
                .expectNext(Map.of("bob", Closeness.CLOSE, "hugo", Closeness.INTIMATE))
                .verifyComplete();
    }

    @Test
    void knownFriendsOf_shouldBeEmpty_whenNoListKnowsTheUser() {
        when(friendLists.findOf("lea")).thenReturn(Mono.empty());
        when(friendLists.findNaming("lea")).thenReturn(Flux.empty());

        StepVerifier.create(directory.knownFriendsOf("lea")).expectNext(Map.of()).verifyComplete();
    }

    @Test
    void forget_shouldDeleteTheUsersList() {
        when(friendLists.delete("lea")).thenReturn(Mono.empty());

        StepVerifier.create(directory.forget("lea")).verifyComplete();
        verify(friendLists).delete("lea");
    }
}
