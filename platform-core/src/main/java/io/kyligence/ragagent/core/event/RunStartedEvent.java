package io.kyligence.ragagent.core.event;

public record RunStartedEvent(String runId, String runType, String workspaceId) {}
