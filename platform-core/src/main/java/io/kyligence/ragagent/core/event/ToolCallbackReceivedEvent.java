package io.kyligence.ragagent.core.event;

public record ToolCallbackReceivedEvent(String runId, String toolCallId, String resultJson) {}
