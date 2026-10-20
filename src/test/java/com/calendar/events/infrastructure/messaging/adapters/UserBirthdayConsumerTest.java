package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.domain.models.Event;
import com.calendar.events.domain.services.BirthdayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.MonthDay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserBirthdayConsumerTest {

    private static final String USER = "89a380b8-f66a-44d0-b9d9-616241080430";
    private static final String OTHER = "1b4e28ba-2fa1-41d2-883f-0016d3cca427";

    @Mock
    private BirthdayService birthdayService;

    private UserBirthdayConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new UserBirthdayConsumer(birthdayService, JsonMapper.builder().build());
    }

    private static Message<byte[]> record(String json) {
        return MessageBuilder.withPayload(json.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static Message<byte[]> birthday(String userId, String name, int month, int day) {
        return record("{\"userId\":\"" + userId + "\",\"displayName\":\"" + name + "\",\"month\":" + month
                + ",\"day\":" + day + "}");
    }

    @SafeVarargs
    private Mono<Void> consume(Message<byte[]>... records) {
        return consumer.userBirthday().apply(Flux.just(records));
    }

    @Test
    void userBirthday_shouldRecordTheBirthdayNamedByTheRecord() {
        when(birthdayService.recordBirthday(USER, "Léa", MonthDay.of(3, 12))).thenReturn(Mono.just(new Event()));

        StepVerifier.create(consume(birthday(USER, "Léa", 3, 12))).expectComplete().verify(Duration.ofSeconds(10));
        verify(birthdayService).recordBirthday(USER, "Léa", MonthDay.of(3, 12));
    }

    @Test
    void userBirthday_shouldAcceptA29February_andAnUppercaseUuidInLowercase() {
        when(birthdayService.recordBirthday(USER, "Léa", MonthDay.of(2, 29))).thenReturn(Mono.just(new Event()));

        StepVerifier.create(consume(birthday(USER.toUpperCase(), " Léa ", 2, 29))).expectComplete().verify(Duration.ofSeconds(10));
        verify(birthdayService).recordBirthday(USER, "Léa", MonthDay.of(2, 29));
    }

    @Test
    void userBirthday_shouldDropARecordWithoutARealDay_aUuidOrAName_andGoOn() {
        when(birthdayService.recordBirthday(OTHER, "Bob", MonthDay.of(1, 1))).thenReturn(Mono.empty());

        StepVerifier.create(consume(
                        birthday(USER, "Léa", 2, 30),
                        birthday("not-a-uuid", "Léa", 3, 12),
                        record("{\"userId\":\"" + USER + "\",\"month\":3,\"day\":12}"),
                        record("{\"userId\":\"" + USER + "\",\"displayName\":\"Léa\"}"),
                        record("not json"),
                        birthday(OTHER, "Bob", 1, 1)))
                .expectComplete().verify(Duration.ofSeconds(10));
        verify(birthdayService).recordBirthday(OTHER, "Bob", MonthDay.of(1, 1));
        verify(birthdayService, never()).recordBirthday(org.mockito.ArgumentMatchers.eq(USER), anyString(), any());
    }

    @Test
    void userBirthday_shouldRetryAFailure_thenGiveUpWithoutStoppingTheStream() {
        when(birthdayService.recordBirthday(USER, "Léa", MonthDay.of(3, 12)))
                .thenReturn(Mono.error(new IllegalStateException("mongo down")));
        when(birthdayService.recordBirthday(OTHER, "Bob", MonthDay.of(1, 1))).thenReturn(Mono.just(new Event()));

        StepVerifier.withVirtualTime(() -> consume(birthday(USER, "Léa", 3, 12), birthday(OTHER, "Bob", 1, 1)))
                .thenAwait(Duration.ofMinutes(5))
                .verifyComplete();
        verify(birthdayService).recordBirthday(OTHER, "Bob", MonthDay.of(1, 1));
    }
}
