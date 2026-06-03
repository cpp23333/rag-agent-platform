package io.kyligence.ragagent.core.event;

public interface PlatformEventBus {

    void publishRunStarted(String runId, String runType, String workspaceId);

    void publishRunFinished(String runId, String status, String workspaceId);

    void publishStepCompleted(String runId, String stepId, String stepName);

    void publishIngestJobCompleted(String jobId, String kbId, String workspaceId,
                                   boolean success, String errorMessage);

    void publishToolCallback(String runId, String toolCallId, String resultJson);
}
