package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.messaging.models.BirthdayNoticeDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaBirthdayNoticePublisherTest {

    private static final BirthdayNoticeDTO NOTICE = new BirthdayNoticeDTO("FRIEND_BIRTHDAY", "bob", "lea", "Léa",
            "2027-03-12", 0, "CLOSE", null, null, null);

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private KafkaBirthdayNoticePublisher publisher;

    private static boolean brokerUnavailable(Throwable error) {
        return error instanceof TechnicalException e && e.getErrorCode() == TechnicalErrorCode.BROKER_UNAVAILABLE;
    }

    @Test
    void publish_shouldSendTheNoticeOnItsBinding_keyedByRecipient() {
        when(streamBridge.send(eq(KafkaBirthdayNoticePublisher.DESTINATION), argThat((Object sent) ->
                sent instanceof Message<?> message && message.getPayload().equals(NOTICE)))).thenReturn(true);

        StepVerifier.create(publisher.publish(NOTICE)).expectComplete().verify(Duration.ofSeconds(10));
    }

    @Test
    void message_shouldCarryTheRecipientAsTheKafkaKey() {
        Message<BirthdayNoticeDTO> message = KafkaBirthdayNoticePublisher.message(NOTICE);

        assertThat(message.getPayload()).isEqualTo(NOTICE);
        assertThat((byte[]) message.getHeaders().get(KafkaHeaders.KEY)).isEqualTo("bob".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void publish_shouldFailAsBrokerUnavailable_whenKafkaRefusesTheRecord() {
        when(streamBridge.send(eq(KafkaBirthdayNoticePublisher.DESTINATION), any(Object.class))).thenReturn(false);

        StepVerifier.create(publisher.publish(NOTICE)).expectErrorMatches(error -> brokerUnavailable(error) && error.getCause() == null).verify(Duration.ofSeconds(10));
    }

    @Test
    void publish_shouldTranslateABinderFailure() {
        MessageDeliveryException failure = new MessageDeliveryException("broker down");
        when(streamBridge.send(eq(KafkaBirthdayNoticePublisher.DESTINATION), any(Object.class)))
                .thenThrow(failure);

        StepVerifier.create(publisher.publish(NOTICE)).expectErrorMatches(error -> brokerUnavailable(error) && error.getCause() == failure).verify(Duration.ofSeconds(10));
    }
}
