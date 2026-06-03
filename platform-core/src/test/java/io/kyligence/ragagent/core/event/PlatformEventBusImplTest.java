package io.kyligence.ragagent.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformEventBusImplTest {

    @Mock private PlatformEventMapper mapper;
    @Mock private ApplicationEventPublisher publisher;

    private PlatformEventBusImpl bus;

    @BeforeEach
    void setUp() {
        bus = new PlatformEventBusImpl(mapper, publisher, new ObjectMapper());
    }

    @Test
    void publishRunStartedPersistsAndPublishes() {
        bus.publishRunStarted("run-1", "AGENT", "ws-1");

        ArgumentCaptor<PlatformEvent> cap = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(mapper).insert(cap.capture());
        PlatformEvent saved = cap.getValue();
        assertThat(saved.getEventType()).isEqualTo(EventType.RUN_STARTED);
        assertThat(saved.getAggregateId()).isEqualTo("run-1");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getPayloadJson()).contains("AGENT");

        verify(publisher).publishEvent(any(RunStartedEvent.class));
    }

    @Test
    void publishRunFinishedPersistsAndPublishes() {
        bus.publishRunFinished("run-2", "SUCCESS", "ws-1");

        verify(mapper).insert(argThat(e ->
            e.getEventType() == EventType.RUN_FINISHED &&
            e.getAggregateId().equals("run-2")));
        verify(publisher).publishEvent(any(RunFinishedEvent.class));
    }

    @Test
    void publishIngestJobCompletedPersistsPayload() {
        bus.publishIngestJobCompleted("job-1", "kb-1", "ws-1", true, null);

        verify(mapper).insert(argThat(e -> {
            assertThat(e.getPayloadJson()).contains("kb-1").contains("true");
            return true;
        }));
    }

    @Test
    void publishToolCallbackPersistsAndPublishes() {
        bus.publishToolCallback("run-3", "tc-1", "{\"result\":\"ok\"}");

        verify(mapper).insert(argThat(e ->
            e.getEventType() == EventType.TOOL_CALLBACK_RECEIVED));
        verify(publisher).publishEvent(any(ToolCallbackReceivedEvent.class));
    }

    @Test
    void eachPublishInsertsExactlyOnce() {
        bus.publishStepCompleted("run-4", "step-1", "llm-call");
        verify(mapper, times(1)).insert(any());
    }
}
