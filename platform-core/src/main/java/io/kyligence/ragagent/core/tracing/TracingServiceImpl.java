package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TracingServiceImpl implements TracingService {

    private final TraceRunMapper runMapper;
    private final TraceStepMapper stepMapper;

    @Override
    @Transactional
    public TraceRun startRun(String workspaceId, RunType type, String parentRunId, String metadataJson) {
        TraceRun run = new TraceRun();
        run.setWorkspaceId(workspaceId);
        run.setType(type);
        run.setStatus(RunStatus.RUNNING);
        run.setParentRunId(parentRunId);
        run.setMetadataJson(metadataJson);
        run.setStartedAt(LocalDateTime.now());
        runMapper.insert(run);
        log.info("Started trace run: id={}, type={}, workspace={}", run.getId(), type, workspaceId);
        return run;
    }

    @Override
    @Transactional
    public TraceRun endRun(String runId, RunStatus status) {
        TraceRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("TraceRun not found: " + runId);
        }
        run.setStatus(status);
        run.setEndedAt(LocalDateTime.now());
        runMapper.updateById(run);
        log.info("Ended trace run: id={}, status={}", runId, status);
        return run;
    }

    @Override
    @Transactional
    public TraceStep addStep(String runId, String name, StepType type,
                             String input, String output, Integer tokens,
                             BigDecimal cost, Long latencyMs) {
        TraceStep step = new TraceStep();
        step.setRunId(runId);
        step.setName(name);
        step.setType(type);
        step.setInput(input);
        step.setOutput(output);
        step.setTokens(tokens);
        step.setCost(cost);
        step.setLatencyMs(latencyMs);
        step.setCreatedAt(LocalDateTime.now());
        stepMapper.insert(step);
        return step;
    }

    @Override
    @Transactional(readOnly = true)
    public TraceRun getRun(String runId) {
        return runMapper.selectById(runId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TraceStep> getSteps(String runId) {
        return stepMapper.selectList(
            Wrappers.<TraceStep>lambdaQuery()
                .eq(TraceStep::getRunId, runId)
                .orderByAsc(TraceStep::getCreatedAt)
        );
    }
}
