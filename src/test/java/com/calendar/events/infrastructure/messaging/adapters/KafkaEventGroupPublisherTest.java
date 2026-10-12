package com.calendar.events.infrastructure.messaging.adapters;

import com.calendar.events.infrastructure.messaging.models.EventGroupChangedDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.stream.function.StreamBridge;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaEventGroupPublisherTest {

    private static final EventGroupChangedDTO GROUP = new EventGroupChangedDTO("e1", "Quiz", List.of("a"), 2);

    @Mock
    private StreamBridge streamBridge;

    @InjectMocks
    private KafkaEventGroupPublisher publisher;

    @Test
    void publish_shouldSendTheGroupOnItsBinding() {
        when(streamBridge.send(KafkaEventGroupPublisher.DESTINATION, GROUP)).thenReturn(true);

        StepVerifier.create(publisher.publish(GROUP)).verifyComplete();

        verify(streamBridge).send(KafkaEventGroupPublisher.DESTINATION, GROUP);
    }

    @Test
    void publish_shouldFail_whenKafkaRefusesTheRecord() {
        when(streamBridge.send(KafkaEventGroupPublisher.DESTINATION, GROUP)).thenReturn(false);

        StepVerifier.create(publisher.publish(GROUP)).verifyError(IllegalStateException.class);
    }

    @Test
    void publish_shouldFail_whenTheBridgeThrows() {
        when(streamBridge.send(KafkaEventGroupPublisher.DESTINATION, GROUP))
                .thenThrow(new IllegalStateException("broker down"));

        StepVerifier.create(publisher.publish(GROUP)).verifyError(IllegalStateException.class);
    }
}
