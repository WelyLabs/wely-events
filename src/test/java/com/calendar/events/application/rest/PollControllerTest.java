package com.calendar.events.application.rest;

import com.calendar.events.domain.services.FriendshipService;
import com.calendar.events.domain.models.ViewerFriends;
import com.calendar.events.application.dtos.BallotRequest;
import com.calendar.events.application.dtos.EventResponse;
import com.calendar.events.application.dtos.FixDateRequest;
import com.calendar.events.application.dtos.PollResponse;
import com.calendar.events.application.dtos.PollSlotResponse;
import com.calendar.events.application.mappers.EventRestMapper;
import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.models.Poll;
import com.calendar.events.domain.models.PollAnswer;
import com.calendar.events.domain.models.PollSlot;
import com.calendar.events.domain.models.PollStatus;
import com.calendar.events.domain.ports.MediaStore;
import com.calendar.events.domain.services.EventService;
import com.calendar.events.domain.services.PictureService;
import com.calendar.events.domain.services.PollService;
import com.calendar.events.exception.BusinessErrorCode;
import com.calendar.events.exception.BusinessException;
import com.calendar.events.exception.GlobalErrorHandler;
import com.calendar.events.exception.TechnicalErrorCounter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.reactive.result.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@ExtendWith(MockitoExtension.class)
class PollControllerTest {

    private static final PollSlot THURSDAY = new PollSlot("thu", Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"));
    private static final PollSlot FRIDAY = new PollSlot("fri", Instant.parse("2026-10-17T17:00:00Z"), Instant.parse("2026-10-17T19:00:00Z"));
    private static final Poll OPEN = new Poll(PollStatus.OPEN, List.of(THURSDAY, FRIDAY), Map.of(), null);
    private static final Poll VOTED = new Poll(PollStatus.OPEN, List.of(THURSDAY, FRIDAY),
            Map.of("lea", Map.of("thu", PollAnswer.YES, "fri", PollAnswer.NO)), null);
    private static final Poll CLOSED = new Poll(PollStatus.CLOSED, List.of(THURSDAY, FRIDAY), VOTED.ballots(), "thu");

    @Mock
    private FriendshipService friendshipService;

    @Mock
    private PollService pollService;
    @Mock
    private MediaStore mediaStore;
    @Mock
    private Jwt jwt;

    private final EventRestMapper mapper = Mappers.getMapper(EventRestMapper.class);
    private PollController controller;

    @BeforeEach
    void setUp() {
        lenient().when(friendshipService.viewerFriends(anyString())).thenReturn(Mono.just(ViewerFriends.NONE));
        controller = new PollController(pollService, new PictureService(mediaStore), friendshipService, mapper,
                Duration.ofSeconds(20));
    }

    private static Event event(Poll poll) {
        return Event.builder().id("e1").title("Dinner").organizerId("org")
                .startDate(THURSDAY.start()).endDate(THURSDAY.end())
                .participantIds(new ArrayList<>()).poll(poll).build();
    }

    @Test
    void castBallot_shouldVoteOnBehalfOfTheCaller_andAnswerThePoll() {
        when(jwt.getClaimAsString("businessId")).thenReturn("lea");
        Map<String, PollAnswer> answers = Map.of("thu", PollAnswer.YES, "fri", PollAnswer.NO);
        when(pollService.vote("e1", "lea", answers)).thenReturn(Mono.just(VOTED));

        StepVerifier.create(controller.castBallot("e1", new BallotRequest(answers), jwt))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(PollStatus.OPEN);
                    assertThat(response.fixedSlotId()).isNull();
                    assertThat(response.slots()).containsExactly(
                            new PollSlotResponse("thu", THURSDAY.start(), THURSDAY.end(), List.of("lea"), List.of(), List.of()),
                            new PollSlotResponse("fri", FRIDAY.start(), FRIDAY.end(), List.of(), List.of(), List.of("lea")));
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void castBallot_shouldPassARefusalThrough() {
        when(jwt.getClaimAsString("businessId")).thenReturn("lea");
        when(pollService.vote("e1", "lea", Map.of())).thenReturn(Mono.error(new BusinessException(BusinessErrorCode.POLL_CLOSED)));

        StepVerifier.create(controller.castBallot("e1", new BallotRequest(Map.of()), jwt))
                .expectErrorMatches(error -> error instanceof BusinessException e && e.getErrorCode() == BusinessErrorCode.POLL_CLOSED)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    void fixDate_shouldFixOnBehalfOfTheCaller_andAnswerTheEventWithItsClosedPoll() {
        when(jwt.getClaimAsString("businessId")).thenReturn("org");
        when(pollService.fix("e1", "org", "thu")).thenReturn(Mono.just(event(CLOSED)));

        StepVerifier.create(controller.fixDate("e1", new FixDateRequest("thu"), jwt))
                .assertNext(response -> {
                    assertThat(response.getId()).isEqualTo("e1");
                    assertThat(response.getPoll().status()).isEqualTo(PollStatus.CLOSED);
                    assertThat(response.getPoll().fixedSlotId()).isEqualTo("thu");
                })
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void getMyPolls_shouldListTheCallersOpenPolls() {
        when(jwt.getClaimAsString("businessId")).thenReturn("lea");
        when(pollService.getMyPolls("lea")).thenReturn(Flux.just(event(OPEN)));

        StepVerifier.create(controller.getMyPolls(jwt))
                .assertNext(response -> assertThat(response.getPoll().slots()).hasSize(2))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void streamPoll_shouldSendPollEvents_withoutProxyBuffering_andEndOnceClosed() {
        when(jwt.getClaimAsString("businessId")).thenReturn("lea");
        when(pollService.watch("e1", "lea")).thenReturn(Mono.just(Flux.just(OPEN, VOTED, CLOSED)));

        ResponseEntity<Flux<ServerSentEvent<PollResponse>>> response = controller.streamPoll("e1", jwt).block();

        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        StepVerifier.create(response.getBody())
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo(PollController.POLL_EVENT);
                    assertThat(sse.data().status()).isEqualTo(PollStatus.OPEN);
                })
                .assertNext(sse -> assertThat(sse.data().slots().getFirst().yes()).containsExactly("lea"))
                .assertNext(sse -> assertThat(sse.data().status()).isEqualTo(PollStatus.CLOSED))
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void streamPoll_shouldInterleaveKeepAliveComments_whileThePollStaysQuiet() {
        when(jwt.getClaimAsString("businessId")).thenReturn("lea");
        Sinks.Many<Poll> states = Sinks.many().unicast().onBackpressureBuffer();
        when(pollService.watch("e1", "lea")).thenReturn(Mono.just(states.asFlux()));

        StepVerifier.withVirtualTime(() -> controller.streamPoll("e1", jwt).flatMapMany(ResponseEntity::getBody))
                .then(() -> states.tryEmitNext(OPEN))
                .assertNext(sse -> assertThat(sse.data().status()).isEqualTo(PollStatus.OPEN))
                .thenAwait(Duration.ofSeconds(20))
                .assertNext(sse -> {
                    assertThat(sse.comment()).isEqualTo(PollController.KEEP_ALIVE_COMMENT);
                    assertThat(sse.data()).isNull();
                })
                .then(() -> states.tryEmitNext(CLOSED))
                .assertNext(sse -> assertThat(sse.data().status()).isEqualTo(PollStatus.CLOSED))
                .then(states::tryEmitComplete)
                .verifyComplete();
    }

    @Test
    void streamPoll_shouldFailBeforeStreaming_whenTheCallerCannotSeeTheEvent() {
        when(jwt.getClaimAsString("businessId")).thenReturn("stranger");
        when(pollService.watch("e1", "stranger")).thenReturn(Mono.error(new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND)));

        StepVerifier.create(controller.streamPoll("e1", jwt))
                .expectErrorMatches(error -> error instanceof BusinessException e && e.getErrorCode() == BusinessErrorCode.EVENT_NOT_FOUND)
                .verify(Duration.ofSeconds(10));
    }

    private WebTestClient client(String userId) {
        // EventController too, so /events/me/polls is shown not to be taken for /events/{id}.
        EventController events = new EventController(org.mockito.Mockito.mock(EventService.class),
                org.mockito.Mockito.mock(com.calendar.events.domain.services.SurpriseService.class),
                new PictureService(mediaStore), friendshipService, mapper);
        return WebTestClient.bindToController(controller, events)
                .argumentResolvers(resolvers -> resolvers.addCustomResolver(
                        new AuthenticationPrincipalArgumentResolver(ReactiveAdapterRegistry.getSharedInstance())))
                .controllerAdvice(new GlobalErrorHandler(new TechnicalErrorCounter(new SimpleMeterRegistry())))
                .apply(springSecurity())
                .configureClient()
                .build()
                .mutateWith(mockJwt().jwt(token -> token.claim("businessId", userId)));
    }

    @Test
    void streamPoll_shouldStreamOverHttp_theFirstStateThenAnUpdate() {
        Sinks.Many<Poll> states = Sinks.many().unicast().onBackpressureBuffer();
        states.tryEmitNext(OPEN);
        when(pollService.watch("e1", "lea")).thenReturn(Mono.just(states.asFlux()));

        Flux<ServerSentEvent<PollResponse>> body = client("lea").get().uri("/events/e1/poll/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<PollResponse>>() { })
                .getResponseBody();

        StepVerifier.create(body)
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("poll");
                    assertThat(sse.data().status()).isEqualTo(PollStatus.OPEN);
                })
                .then(() -> states.tryEmitNext(VOTED))
                .assertNext(sse -> assertThat(sse.data().slots().getFirst().yes()).containsExactly("lea"))
                .then(() -> states.tryEmitNext(CLOSED))
                .assertNext(sse -> assertThat(sse.data().fixedSlotId()).isEqualTo("thu"))
                .then(states::tryEmitComplete)
                .expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void streamPoll_shouldAnswer404OverHttp_whenTheCallerCannotSeeTheEvent() {
        when(pollService.watch("e1", "stranger")).thenReturn(Mono.error(new BusinessException(BusinessErrorCode.EVENT_NOT_FOUND)));

        client("stranger").get().uri("/events/e1/poll/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void castBallot_shouldReadTheBallotOverHttp() {
        when(pollService.vote("e1", "lea", Map.of("thu", PollAnswer.MAYBE))).thenReturn(Mono.just(VOTED));

        client("lea").put().uri("/events/e1/poll/ballot")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"answers\":{\"thu\":\"MAYBE\"}}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("OPEN")
                .jsonPath("$.fixedSlotId").doesNotExist()
                .jsonPath("$.slots[0].id").isEqualTo("thu")
                .jsonPath("$.slots[0].start").isEqualTo("2026-10-16T17:00:00Z")
                .jsonPath("$.slots[0].yes[0]").isEqualTo("lea")
                .jsonPath("$.slots[1].no[0]").isEqualTo("lea");
    }

    @Test
    void getMyPolls_shouldNotBeTakenForAnEventId() {
        when(pollService.getMyPolls("lea")).thenReturn(Flux.just(event(OPEN)));

        client("lea").get().uri("/events/me/polls")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(EventResponse.class).hasSize(1);
    }
}
