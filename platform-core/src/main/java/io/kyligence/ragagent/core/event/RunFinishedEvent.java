package io.kyligence.ragagent.core.event;

public record RunFinishedEvent(String runId, String status, String workspaceId) {}
