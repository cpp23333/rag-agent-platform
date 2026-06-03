package io.kyligence.ragagent.core.event;

public record StepCompletedEvent(String runId, String stepId, String stepName) {}
