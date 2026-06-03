# B7 Spec: EventBus

## Context

EventBus 是 Platform Core 的事件总线（架构设计 §3.6）。使用 Spring `ApplicationEventPublisher` 发布事件，配合持久化事件表实现异步消费和幂等性。主要服务于 Agent/Workflow Run 生命周期通知、Ingest 完成回调、长耗时工具回调。

## 目标

- 统一事件发布接口（`PlatformEventBus`）
- 事件持久化到 DB（`platform_event` 表），保证不丢失
- 异步消费：Spring `@Async` + `@TransactionalEventListener`
- 幂等消费：`event_id` 唯一键防重
- 一期事件类型：`RunStarted` / `RunFinished` / `StepCompleted` / `IngestJobCompleted` / `ToolCallbackReceived`

## 范围

`platform-core` 模块。无 REST controller（事件总线是内部能力）。

---

## File Structure

```
platform-core/src/main/resources/db/changelog/changes/
  012-platform-event.yaml

platform-core/src/main/java/io/kyligence/ragagent/core/event/
  EventType.java
  PlatformEvent.java              # DB 实体
  PlatformEventMapper.java
  RunStartedEvent.java
  RunFinishedEvent.java
  StepCompletedEvent.java
  IngestJobCompletedEvent.java
  ToolCallbackReceivedEvent.java
  PlatformEventBus.java           # 接口
  PlatformEventBusImpl.java
  EventConsumer.java              # @TransactionalEventListener 分发

platform-core/src/test/java/io/kyligence/ragagent/core/event/
  PlatformEventBusImplTest.java
```

---

## Phase 1: DB Schema

### File: `012-platform-event.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 012-platform-event
      author: ragagent
      changes:
        - createTable:
            tableName: platform_event
            columns:
              - column: { name: id, type: VARCHAR(36), constraints: { primaryKey: true, nullable: false } }
              - column: { name: event_type, type: VARCHAR(64), constraints: { nullable: false } }
              - column: { name: aggregate_id, type: VARCHAR(36), constraints: { nullable: false } }
              - column: { name: workspace_id, type: VARCHAR(36) }
              - column: { name: payload_json, type: TEXT, constraints: { nullable: false } }
              - column: { name: status, type: VARCHAR(16), defaultValue: "PENDING", constraints: { nullable: false } }
              - column: { name: created_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: processed_at, type: DATETIME }
        - createIndex:
            tableName: platform_event
            indexName: idx_event_aggregate
            columns:
              - column: { name: aggregate_id }
        - createIndex:
            tableName: platform_event
            indexName: idx_event_status_type
            columns:
              - column: { name: status }
              - column: { name: event_type }
```

设计说明：
- `status`：`PENDING` / `PROCESSED` / `FAILED`
- `aggregate_id`：run_id / job_id 等，便于查询某个 Run 的所有事件
- 幂等由 `id`（UUID）保证；消费者先查是否已 `PROCESSED` 再处理

---

## Phase 2: Event Types & Domain Classes

### File: `core/event/EventType.java`

```java
package io.kyligence.ragagent.core.event;

public enum EventType {
    RUN_STARTED,
    RUN_FINISHED,
    STEP_COMPLETED,
    INGEST_JOB_COMPLETED,
    TOOL_CALLBACK_RECEIVED
}
```

### File: `core/event/PlatformEvent.java`（DB 实体）

```java
package io.kyligence.ragagent.core.event;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("platform_event")
public class PlatformEvent {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private EventType eventType;
    private String aggregateId;
    private String workspaceId;
    private String payloadJson;
    private String status;          // PENDING / PROCESSED / FAILED
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}
```

### File: `core/event/PlatformEventMapper.java`

```java
package io.kyligence.ragagent.core.event;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlatformEventMapper extends BaseMapper<PlatformEvent> {
}
```

---

## Phase 3: Spring Application Events（内存传递载体）

以下 record 仅用于 Spring `ApplicationEventPublisher` 内存传递，不直接存 DB。

### File: `core/event/RunStartedEvent.java`

```java
package io.kyligence.ragagent.core.event;

public record RunStartedEvent(String runId, String runType, String workspaceId) {}
```

### File: `core/event/RunFinishedEvent.java`

```java
package io.kyligence.ragagent.core.event;

public record RunFinishedEvent(String runId, String status, String workspaceId) {}
```

### File: `core/event/StepCompletedEvent.java`

```java
package io.kyligence.ragagent.core.event;

public record StepCompletedEvent(String runId, String stepId, String stepName) {}
```

### File: `core/event/IngestJobCompletedEvent.java`

```java
package io.kyligence.ragagent.core.event;

public record IngestJobCompletedEvent(String jobId, String kbId, String workspaceId,
                                      boolean success, String errorMessage) {}
```

### File: `core/event/ToolCallbackReceivedEvent.java`

```java
package io.kyligence.ragagent.core.event;

public record ToolCallbackReceivedEvent(String runId, String toolCallId,
                                        String resultJson) {}
```

---

## Phase 4: EventBus Interface & Implementation

### File: `core/event/PlatformEventBus.java`

```java
package io.kyligence.ragagent.core.event;

public interface PlatformEventBus {

    void publishRunStarted(String runId, String runType, String workspaceId);

    void publishRunFinished(String runId, String status, String workspaceId);

    void publishStepCompleted(String runId, String stepId, String stepName);

    void publishIngestJobCompleted(String jobId, String kbId, String workspaceId,
                                   boolean success, String errorMessage);

    void publishToolCallback(String runId, String toolCallId, String resultJson);
}
```

### File: `core/event/PlatformEventBusImpl.java`

发布时同步写 DB（事务内），然后用 `ApplicationEventPublisher` 触发异步消费。

```java
package io.kyligence.ragagent.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformEventBusImpl implements PlatformEventBus {

    private final PlatformEventMapper mapper;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void publishRunStarted(String runId, String runType, String workspaceId) {
        persist(EventType.RUN_STARTED, runId, workspaceId,
            Map.of("runId", runId, "runType", runType));
        publisher.publishEvent(new RunStartedEvent(runId, runType, workspaceId));
    }

    @Override
    @Transactional
    public void publishRunFinished(String runId, String status, String workspaceId) {
        persist(EventType.RUN_FINISHED, runId, workspaceId,
            Map.of("runId", runId, "status", status));
        publisher.publishEvent(new RunFinishedEvent(runId, status, workspaceId));
    }

    @Override
    @Transactional
    public void publishStepCompleted(String runId, String stepId, String stepName) {
        persist(EventType.STEP_COMPLETED, runId, null,
            Map.of("stepId", stepId, "stepName", stepName));
        publisher.publishEvent(new StepCompletedEvent(runId, stepId, stepName));
    }

    @Override
    @Transactional
    public void publishIngestJobCompleted(String jobId, String kbId, String workspaceId,
                                          boolean success, String errorMessage) {
        persist(EventType.INGEST_JOB_COMPLETED, jobId, workspaceId,
            Map.of("kbId", kbId, "success", success,
                   "errorMessage", errorMessage == null ? "" : errorMessage));
        publisher.publishEvent(
            new IngestJobCompletedEvent(jobId, kbId, workspaceId, success, errorMessage));
    }

    @Override
    @Transactional
    public void publishToolCallback(String runId, String toolCallId, String resultJson) {
        persist(EventType.TOOL_CALLBACK_RECEIVED, runId, null,
            Map.of("toolCallId", toolCallId, "resultJson", resultJson));
        publisher.publishEvent(new ToolCallbackReceivedEvent(runId, toolCallId, resultJson));
    }

    private void persist(EventType type, String aggregateId,
                         String workspaceId, Map<String, Object> payload) {
        try {
            PlatformEvent event = new PlatformEvent();
            event.setEventType(type);
            event.setAggregateId(aggregateId);
            event.setWorkspaceId(workspaceId);
            event.setPayloadJson(objectMapper.writeValueAsString(payload));
            event.setStatus("PENDING");
            event.setCreatedAt(LocalDateTime.now());
            mapper.insert(event);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.INTERNAL,
                "Failed to persist event: " + e.getMessage(), e);
        }
    }
}
```

---

## Phase 5: Event Consumer

事件消费者监听 Spring 应用事件，标记 DB 记录为 `PROCESSED`。后续 Agent/Workflow 模块按需扩展 `@EventListener` 方法。

### File: `core/event/EventConsumer.java`

```java
package io.kyligence.ragagent.core.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventConsumer {

    private final PlatformEventMapper mapper;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunStarted(RunStartedEvent event) {
        log.info("Run started: runId={}, type={}", event.runId(), event.runType());
        markProcessed(event.runId(), EventType.RUN_STARTED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunFinished(RunFinishedEvent event) {
        log.info("Run finished: runId={}, status={}", event.runId(), event.status());
        markProcessed(event.runId(), EventType.RUN_FINISHED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIngestCompleted(IngestJobCompletedEvent event) {
        log.info("Ingest job completed: jobId={}, success={}", event.jobId(), event.success());
        markProcessed(event.jobId(), EventType.INGEST_JOB_COMPLETED);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onToolCallback(ToolCallbackReceivedEvent event) {
        log.info("Tool callback received: runId={}, toolCallId={}",
            event.runId(), event.toolCallId());
        markProcessed(event.runId(), EventType.TOOL_CALLBACK_RECEIVED);
    }

    private void markProcessed(String aggregateId, EventType type) {
        PlatformEvent event = mapper.selectOne(
            Wrappers.<PlatformEvent>lambdaQuery()
                .eq(PlatformEvent::getAggregateId, aggregateId)
                .eq(PlatformEvent::getEventType, type)
                .eq(PlatformEvent::getStatus, "PENDING")
                .orderByDesc(PlatformEvent::getCreatedAt)
                .last("LIMIT 1"));
        if (event != null) {
            event.setStatus("PROCESSED");
            event.setProcessedAt(LocalDateTime.now());
            mapper.updateById(event);
        }
    }
}
```

---

## Phase 6: Tests

### File: `core/event/PlatformEventBusImplTest.java`

```java
package io.kyligence.ragagent.core.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformEventBusImplTest {

    @Mock private PlatformEventMapper mapper;
    @Mock private ApplicationEventPublisher publisher;

    private PlatformEventBusImpl bus;

    @BeforeEach
    void setUp() {
        bus = new PlatformEventBusImpl(mapper, publisher, new ObjectMapper());
    }

    @Test
    void publishRunStartedPersistsAndPublishes() {
        bus.publishRunStarted("run-1", "AGENT", "ws-1");

        ArgumentCaptor<PlatformEvent> cap = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(mapper).insert(cap.capture());
        PlatformEvent saved = cap.getValue();
        assertThat(saved.getEventType()).isEqualTo(EventType.RUN_STARTED);
        assertThat(saved.getAggregateId()).isEqualTo("run-1");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getPayloadJson()).contains("AGENT");

        verify(publisher).publishEvent(any(RunStartedEvent.class));
    }

    @Test
    void publishRunFinishedPersistsAndPublishes() {
        bus.publishRunFinished("run-2", "SUCCESS", "ws-1");

        verify(mapper).insert(argThat(e ->
            e.getEventType() == EventType.RUN_FINISHED &&
            e.getAggregateId().equals("run-2")));
        verify(publisher).publishEvent(any(RunFinishedEvent.class));
    }

    @Test
    void publishIngestJobCompletedPersistsPayload() {
        bus.publishIngestJobCompleted("job-1", "kb-1", "ws-1", true, null);

        verify(mapper).insert(argThat(e -> {
            assertThat(e.getPayloadJson()).contains("kb-1").contains("true");
            return true;
        }));
    }

    @Test
    void publishToolCallbackPersistsAndPublishes() {
        bus.publishToolCallback("run-3", "tc-1", "{\"result\":\"ok\"}");

        verify(mapper).insert(argThat(e ->
            e.getEventType() == EventType.TOOL_CALLBACK_RECEIVED));
        verify(publisher).publishEvent(any(ToolCallbackReceivedEvent.class));
    }

    @Test
    void eachPublishInsertsExactlyOnce() {
        bus.publishStepCompleted("run-4", "step-1", "llm-call");
        verify(mapper, times(1)).insert(any());
    }
}
```

---

## Acceptance Criteria

1. `mvn -pl platform-core -am compile` 通过
2. `mvn -pl platform-core -Dtest=PlatformEventBusImplTest test` 全绿（5 个测试）
3. 已有测试不被破坏
4. Liquibase `012-platform-event.yaml` 被 master changelog 自动加载
5. `@Async` 消费在事务提交后触发（`AFTER_COMMIT`），不影响主事务

## Dependencies

- Jackson `ObjectMapper` 由 Spring Boot 自动配置 ✓
- `@Async` 需在 `server` 的 `@SpringBootApplication` 或配置类加 `@EnableAsync` ✓（Plan A Application.java 可加）
- 无新外部依赖

## 设计决策

| 决策 | 理由 |
|------|------|
| Spring ApplicationEventPublisher 而非 MQ | 单体架构，进程内足够；MQ 引入部署复杂度 |
| 发布时同步写 DB | 保证事件不因进程崩溃丢失；DB 是持久化凭证 |
| AFTER_COMMIT 消费 | 确保主业务事务提交后再触发消费，避免消费成功但主事务回滚 |
| 幂等由 event.id UUID 保证 | 每次 publish 生成新 UUID；消费者标记 PROCESSED 后不重复处理 |
| 消费者仅打 log + 标记状态 | 具体响应逻辑由 Agent/Workflow 模块自行添加 `@EventListener` |
