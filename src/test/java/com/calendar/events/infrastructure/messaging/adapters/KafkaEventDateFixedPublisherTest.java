package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.exception.EventErrorCode;
import com.calendar.events.exception.EventException;
import com.calendar.events.infrastructure.messaging.models.EventDateFixedDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaEventDateFixedPublisherTest {

    private static final EventDateFixedDTO FIXED = new EventDateFixedDTO("e1", "Dinner",
            Instant.parse("2026-10-16T17:00:00Z"), Instant.parse("2026-10-16T19:00:00Z"), "org");

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private KafkaEventDateFixedPublisher publisher;

    private static boolean brokerUnavailable(Throwable error) {
        return error instanceof EventException e && e.getErrorCode() == EventErrorCode.BROKER_UNAVAILABLE;
    }

    @Test
    void publish_shouldSendTheDateOnItsBinding() {
        when(streamBridge.send(KafkaEventDateFixedPublisher.DESTINATION, FIXED)).thenReturn(true);

        StepVerifier.create(publisher.publish(FIXED)).verifyComplete();

        verify(streamBridge).send(KafkaEventDateFixedPublisher.DESTINATION, FIXED);
        assertThat(KafkaEventDateFixedPublisher.DESTINATION).isEqualTo("eventDateFixed-out-0");
    }

    @Test
    void publish_shouldFailAsBrokerUnavailable_whenKafkaRefusesTheRecord() {
        when(streamBridge.send(KafkaEventDateFixedPublisher.DESTINATION, FIXED)).thenReturn(false);

        StepVerifier.create(publisher.publish(FIXED))
                .expectErrorMatches(KafkaEventDateFixedPublisherTest::brokerUnavailable)
                .verify();
    }

    @Test
    @DisplayName("the binder's own exception does not leak out of the adapter")
    void publish_shouldTranslateABinderFailure() {
        when(streamBridge.send(KafkaEventDateFixedPublisher.DESTINATION, FIXED))
                .thenThrow(new org.springframework.messaging.MessageDeliveryException("broker down"));

        StepVerifier.create(publisher.publish(FIXED))
                .expectErrorMatches(KafkaEventDateFixedPublisherTest::brokerUnavailable)
                .verify();
    }
}
