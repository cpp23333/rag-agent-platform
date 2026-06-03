package io.kyligence.ragagent.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformEventBusImpl implements PlatformEventBus {

    private final PlatformEventMapper mapper;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void publishRunStarted(String runId, String runType, String workspaceId) {
        persist(EventType.RUN_STARTED, runId, workspaceId,
            Map.of("runId", runId, "runType", runType));
        publisher.publishEvent(new RunStartedEvent(runId, runType, workspaceId));
    }

    @Override
    @Transactional
    public void publishRunFinished(String runId, String status, String workspaceId) {
        persist(EventType.RUN_FINISHED, runId, workspaceId,
            Map.of("runId", runId, "status", status));
        publisher.publishEvent(new RunFinishedEvent(runId, status, workspaceId));
    }

    @Override
    @Transactional
    public void publishStepCompleted(String runId, String stepId, String stepName) {
        persist(EventType.STEP_COMPLETED, runId, null,
            Map.of("stepId", stepId, "stepName", stepName));
        publisher.publishEvent(new StepCompletedEvent(runId, stepId, stepName));
    }

    @Override
    @Transactional
    public void publishIngestJobCompleted(String jobId, String kbId, String workspaceId,
                                          boolean success, String errorMessage) {
        persist(EventType.INGEST_JOB_COMPLETED, jobId, workspaceId,
            Map.of("kbId", kbId, "success", success,
                   "errorMessage", errorMessage == null ? "" : errorMessage));
        publisher.publishEvent(
            new IngestJobCompletedEvent(jobId, kbId, workspaceId, success, errorMessage));
    }

    @Override
    @Transactional
    public void publishToolCallback(String runId, String toolCallId, String resultJson) {
        persist(EventType.TOOL_CALLBACK_RECEIVED, runId, null,
            Map.of("toolCallId", toolCallId, "resultJson", resultJson));
        publisher.publishEvent(new ToolCallbackReceivedEvent(runId, toolCallId, resultJson));
    }

    private void persist(EventType type, String aggregateId,
                         String workspaceId, Map<String, Object> payload) {
        try {
            PlatformEvent event = new PlatformEvent();
            event.setEventType(type);
            event.setAggregateId(aggregateId);
            event.setWorkspaceId(workspaceId);
            event.setPayloadJson(objectMapper.writeValueAsString(payload));
            event.setStatus("PENDING");
            event.setCreatedAt(LocalDateTime.now());
            mapper.insert(event);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to persist event: " + e.getMessage(), e);
        }
    }
}
