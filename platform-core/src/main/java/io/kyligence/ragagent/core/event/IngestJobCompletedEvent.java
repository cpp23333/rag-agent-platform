package io.kyligence.ragagent.core.event;

public record IngestJobCompletedEvent(String jobId, String kbId, String workspaceId,
                                      boolean success, String errorMessage) {}
