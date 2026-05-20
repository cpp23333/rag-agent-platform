# B1 Phase 0+1 Spec: Dependencies + Tracing Subsystem

## Context
Plan B1 implements Platform Core capabilities. This spec covers Phase 0 (pom dependencies) and Phase 1 (Tracing subsystem — the first and most foundational capability).

## Phase 0: Dependency Changes

### File: `pom.xml` (root, at repo root)

1. Add to `<properties>` (after existing `<liquibase.version>` line):
```xml
<resilience4j.version>2.2.0</resilience4j.version>
<pebble.version>3.2.2</pebble.version>
<janino.version>3.1.12</janino.version>
```

2. Add to `<dependencyManagement><dependencies>` (after the testcontainers-bom entry):
```xml
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-bom</artifactId>
    <version>${resilience4j.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
<dependency>
    <groupId>io.pebbletemplates</groupId>
    <artifactId>pebble</artifactId>
    <version>${pebble.version}</version>
</dependency>
<dependency>
    <groupId>org.codehaus.janino</groupId>
    <artifactId>janino</artifactId>
    <version>${janino.version}</version>
</dependency>
```

### File: `platform-core/pom.xml`

Add these dependencies (after the `jakarta.validation-api` block, before Lombok):
```xml
<!-- Resilience4j retry -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-retry</artifactId>
</dependency>

<!-- Pebble template engine -->
<dependency>
    <groupId>io.pebbletemplates</groupId>
    <artifactId>pebble</artifactId>
</dependency>

<!-- Janino sandbox compiler -->
<dependency>
    <groupId>org.codehaus.janino</groupId>
    <artifactId>janino</artifactId>
</dependency>

<!-- Jackson for JSON columns -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### File: `server/pom.xml`

Add after the `spring-boot-starter-security` dependency:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

### File: `shared/src/main/java/io/kyligence/ragagent/shared/exception/ErrorCode.java`

Add Platform Core error codes (7000-7999) after the Workflow section:
```java
// Platform Core 错误 (7000-7999)
MODEL_PROVIDER_NOT_FOUND("7000", "Model provider not found"),
MODEL_CALL_FAILED("7001", "Model call failed"),
MODEL_TIMEOUT("7002", "Model call timeout"),
TOOL_NOT_FOUND("7003", "Tool not found"),
TOOL_INVOKE_FAILED("7004", "Tool invocation failed"),
PROMPT_NOT_FOUND("7005", "Prompt not found"),
PROMPT_RENDER_FAILED("7006", "Prompt render failed"),
SANDBOX_EXECUTION_FAILED("7007", "Sandbox execution failed"),
SANDBOX_TIMEOUT("7008", "Sandbox execution timeout"),
SANDBOX_SECURITY_VIOLATION("7009", "Sandbox security violation");
```

---

## Phase 1: Tracing Subsystem

All files go under `platform-core/src/main/java/io/kyligence/ragagent/core/tracing/`.

### File: `platform-core/src/main/resources/db/changelog/changes/005-trace-run.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 005-trace-run
      author: ragagent
      changes:
        - createTable:
            tableName: trace_run
            columns:
              - column: { name: id, type: VARCHAR(36), constraints: { primaryKey: true, nullable: false } }
              - column: { name: workspace_id, type: VARCHAR(36), constraints: { nullable: false } }
              - column: { name: type, type: VARCHAR(20), constraints: { nullable: false } }
              - column: { name: status, type: VARCHAR(20), constraints: { nullable: false } }
              - column: { name: parent_run_id, type: VARCHAR(36) }
              - column: { name: metadata_json, type: TEXT }
              - column: { name: started_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: ended_at, type: DATETIME }
        - createIndex:
            tableName: trace_run
            indexName: idx_trace_run_workspace
            columns:
              - column: { name: workspace_id }
        - createIndex:
            tableName: trace_run
            indexName: idx_trace_run_type_status
            columns:
              - column: { name: type }
              - column: { name: status }
```

### File: `platform-core/src/main/resources/db/changelog/changes/006-trace-step.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 006-trace-step
      author: ragagent
      changes:
        - createTable:
            tableName: trace_step
            columns:
              - column: { name: id, type: VARCHAR(36), constraints: { primaryKey: true, nullable: false } }
              - column: { name: run_id, type: VARCHAR(36), constraints: { nullable: false } }
              - column: { name: name, type: VARCHAR(128), constraints: { nullable: false } }
              - column: { name: type, type: VARCHAR(20), constraints: { nullable: false } }
              - column: { name: input, type: LONGTEXT }
              - column: { name: output, type: LONGTEXT }
              - column: { name: tokens, type: INT }
              - column: { name: cost, type: DECIMAL(10,6) }
              - column: { name: latency_ms, type: BIGINT }
              - column: { name: created_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - createIndex:
            tableName: trace_step
            indexName: idx_trace_step_run
            columns:
              - column: { name: run_id }
```

### File: `core/tracing/RunType.java`

```java
package io.kyligence.ragagent.core.tracing;

public enum RunType {
    AGENT, WORKFLOW, INGEST, EVAL
}
```

### File: `core/tracing/RunStatus.java`

```java
package io.kyligence.ragagent.core.tracing;

public enum RunStatus {
    RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED
}
```

### File: `core/tracing/StepType.java`

```java
package io.kyligence.ragagent.core.tracing;

public enum StepType {
    LLM, TOOL, NODE, RETRIEVER, CODE, RERANK
}
```

### File: `core/tracing/TraceRun.java`

```java
package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("trace_run")
public class TraceRun {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String workspaceId;
    private RunType type;
    private RunStatus status;
    private String parentRunId;
    private String metadataJson;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
}
```

### File: `core/tracing/TraceStep.java`

```java
package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("trace_step")
public class TraceStep {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String runId;
    private String name;
    private StepType type;
    private String input;
    private String output;
    private Integer tokens;
    private BigDecimal cost;
    private Long latencyMs;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
```

### File: `core/tracing/TraceRunMapper.java`

```java
package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@WorkspaceAware
public interface TraceRunMapper extends BaseMapper<TraceRun> {
}
```

### File: `core/tracing/TraceStepMapper.java`

```java
package io.kyligence.ragagent.core.tracing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TraceStepMapper extends BaseMapper<TraceStep> {
}
```

Note: TraceStepMapper does NOT have @WorkspaceAware — steps are accessed via run_id.

### File: `core/tracing/TracingService.java`

```java
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
```

### File: `core/tracing/TracingServiceImpl.java`

```java
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
```

### Test File: `platform-core/src/test/java/io/kyligence/ragagent/core/tracing/TracingServiceImplTest.java`

```java
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
import static org.mockito.Mockito.*;

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
        void createsRunWithCorrectFields() {
            when(runMapper.insert(any())).thenReturn(1);
            service.startRun("ws-1", RunType.AGENT, null, "{\"key\":\"val\"}");
            verify(runMapper).insert(runCaptor.capture());
            TraceRun captured = runCaptor.getValue();
            assertThat(captured.getWorkspaceId()).isEqualTo("ws-1");
            assertThat(captured.getType()).isEqualTo(RunType.AGENT);
            assertThat(captured.getStatus()).isEqualTo(RunStatus.RUNNING);
            assertThat(captured.getStartedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("endRun")
    class EndRun {
        @Test
        void updatesStatusAndEndedAt() {
            TraceRun existing = new TraceRun();
            existing.setId("run-1");
            existing.setStatus(RunStatus.RUNNING);
            when(runMapper.selectById("run-1")).thenReturn(existing);
            when(runMapper.updateById(any())).thenReturn(1);

            TraceRun result = service.endRun("run-1", RunStatus.SUCCESS);
            assertThat(result.getStatus()).isEqualTo(RunStatus.SUCCESS);
            assertThat(result.getEndedAt()).isNotNull();
        }

        @Test
        void throwsWhenRunNotFound() {
            when(runMapper.selectById("missing")).thenReturn(null);
            assertThatThrownBy(() -> service.endRun("missing", RunStatus.FAILED))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("addStep")
    class AddStep {
        @Test
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
            assertThat(captured.getTokens()).isEqualTo(150);
        }
    }
}
```

## Acceptance Criteria

1. `mvn -pl shared compile` passes (ErrorCode changes compile)
2. `mvn -pl platform-core -am compile` passes (new tracing classes compile)
3. `mvn -pl platform-core test -Dtest=TracingServiceImplTest` passes (unit test green)
4. `mvn -pl server -am compile` passes (webflux dependency resolves)
5. No existing tests broken
