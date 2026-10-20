package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.exception.TechnicalErrorCode;
import com.calendar.events.exception.TechnicalException;
import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageDeliveryException;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaEventGroupPublisherTest {

    private static final EventGroupChangedDTO GROUP = new EventGroupChangedDTO("e1", "Quiz", "a", List.of("a"), 2, false);

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private KafkaEventGroupPublisher publisher;

    @Test
    void publish_shouldSendTheGroupOnItsBinding() {
        when(streamBridge.send(KafkaEventGroupPublisher.DESTINATION, GROUP)).thenReturn(true);

        StepVerifier.create(publisher.publish(GROUP)).expectComplete().verify(Duration.ofSeconds(10));

        verify(streamBridge).send(KafkaEventGroupPublisher.DESTINATION, GROUP);
    }

    private static boolean brokerUnavailable(Throwable error) {
        return error instanceof TechnicalException e && e.getErrorCode() == TechnicalErrorCode.BROKER_UNAVAILABLE;
    }

    @Test
    void publish_shouldFailAsBrokerUnavailable_whenKafkaRefusesTheRecord() {
        when(streamBridge.send(KafkaEventGroupPublisher.DESTINATION, GROUP)).thenReturn(false);

        StepVerifier.create(publisher.publish(GROUP))
                .expectErrorMatches(error -> brokerUnavailable(error) && error.getCause() == null)
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the binder's own exception does not leak out of the adapter")
    void publish_shouldTranslateABinderFailure() {
        MessageDeliveryException failure = new MessageDeliveryException("broker down");
        when(streamBridge.send(KafkaEventGroupPublisher.DESTINATION, GROUP))
                .thenThrow(failure);

        StepVerifier.create(publisher.publish(GROUP))
                .expectErrorMatches(error -> brokerUnavailable(error) && error.getCause() == failure)
                .verify(Duration.ofSeconds(10));
    }
}
