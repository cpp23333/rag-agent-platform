package io.kyligence.ragagent.core.tracing;

import java.math.BigDecimal;
import java.util.List;

public interface TracingService {
    TraceRun startRun(String workspaceId, RunType type, String parentRunId, String metadataJson);
    TraceRun endRun(String runId, RunStatus status);
    TraceStep addStep(String runId, String name, StepType type,
                      String input, String output, Integer tokens,
                      BigDecimal cost, Long latencyMs);
    TraceRun getRun(String runId);
    List<TraceStep> getSteps(String runId);
}
