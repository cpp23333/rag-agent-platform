package io.kyligence.ragagent.core.tracing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TracingServiceImpl")
class TracingServiceImplTest {

    @Mock private TraceRunMapper runMapper;
    @Mock private TraceStepMapper stepMapper;
    @Captor private ArgumentCaptor<TraceRun> runCaptor;
    @Captor private ArgumentCaptor<TraceStep> stepCaptor;

    private TracingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TracingServiceImpl(runMapper, stepMapper);
    }

    @Nested
    @DisplayName("startRun")
    class StartRun {
        @Test
        @DisplayName("creates run with workspace, type, RUNNING status, and startedAt")
        void createsRunWithCorrectFields() {
            when(runMapper.insert(any())).thenReturn(1);

            TraceRun result = service.startRun("ws-1", RunType.AGENT, null, "{\"key\":\"val\"}");

            verify(runMapper).insert(runCaptor.capture());
            TraceRun captured = runCaptor.getValue();
            assertThat(captured.getWorkspaceId()).isEqualTo("ws-1");
            assertThat(captured.getType()).isEqualTo(RunType.AGENT);
            assertThat(captured.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(captured.getParentRunId()).isNull();
            assertThat(captured.getMetadataJson()).isEqualTo("{\"key\":\"val\"}");
            assertThat(captured.getStartedAt()).isNotNull();
            assertThat(result).isSameAs(captured);
        }

        @Test
        @DisplayName("supports nested runs via parentRunId")
        void supportsNestedRuns() {
            when(runMapper.insert(any())).thenReturn(1);

            service.startRun("ws-2", RunType.WORKFLOW, "parent-run-id", null);

            verify(runMapper).insert(runCaptor.capture());
            assertThat(runCaptor.getValue().getParentRunId()).isEqualTo("parent-run-id");
        }
    }

    @Nested
    @DisplayName("endRun")
    class EndRun {
        @Test
        @DisplayName("updates status and endedAt for existing run")
        void updatesStatusAndEndedAt() {
            TraceRun existing = new TraceRun();
            existing.setId("run-1");
            existing.setStatus(RunStatus.RUNNING);
            when(runMapper.selectById("run-1")).thenReturn(existing);
            when(runMapper.updateById(any())).thenReturn(1);

            TraceRun result = service.endRun("run-1", RunStatus.SUCCESS);

            assertThat(result.getStatus()).isEqualTo(RunStatus.SUCCESS);
            assertThat(result.getEndedAt()).isNotNull();
            verify(runMapper).updateById(any(TraceRun.class));
        }

        @Test
        @DisplayName("throws IllegalArgumentException when run not found")
        void throwsWhenRunNotFound() {
            when(runMapper.selectById("missing")).thenReturn(null);

            assertThatThrownBy(() -> service.endRun("missing", RunStatus.FAILED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        }
    }

    @Nested
    @DisplayName("addStep")
    class AddStep {
        @Test
        @DisplayName("inserts step with all fields populated")
        void insertsStepWithAllFields() {
            when(stepMapper.insert(any())).thenReturn(1);

            service.addStep("run-1", "llm-call", StepType.LLM,
                "{\"prompt\":\"hi\"}", "{\"text\":\"hello\"}",
                150, new BigDecimal("0.001500"), 320L);

            verify(stepMapper).insert(stepCaptor.capture());
            TraceStep captured = stepCaptor.getValue();
            assertThat(captured.getRunId()).isEqualTo("run-1");
            assertThat(captured.getName()).isEqualTo("llm-call");
            assertThat(captured.getType()).isEqualTo(StepType.LLM);
            assertThat(captured.getInput()).isEqualTo("{\"prompt\":\"hi\"}");
            assertThat(captured.getOutput()).isEqualTo("{\"text\":\"hello\"}");
            assertThat(captured.getTokens()).isEqualTo(150);
            assertThat(captured.getCost()).isEqualByComparingTo(new BigDecimal("0.001500"));
            assertThat(captured.getLatencyMs()).isEqualTo(320L);
            assertThat(captured.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("getRun")
    class GetRun {
        @Test
        @DisplayName("delegates to mapper.selectById")
        void delegatesToMapper() {
            TraceRun run = new TraceRun();
            run.setId("run-1");
            when(runMapper.selectById("run-1")).thenReturn(run);

            assertThat(service.getRun("run-1")).isSameAs(run);
        }
    }

    @Nested
    @DisplayName("getSteps")
    class GetSteps {
        @Test
        @DisplayName("returns steps filtered by runId, ordered by createdAt")
        void returnsStepsForRun() {
            TraceStep s1 = new TraceStep();
            s1.setRunId("run-1");
            when(stepMapper.selectList(any())).thenReturn(List.of(s1));

            List<TraceStep> result = service.getSteps("run-1");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRunId()).isEqualTo("run-1");
        }
    }
}