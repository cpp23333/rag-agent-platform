# EventBus 功能分析与生产调用链

> 对应 spec：`docs/specs/b7-event-bus.md`
> 写于 2026-06-08

## 一、定位与职责

EventBus 是 Platform Core 的事件总线，使用 Spring ApplicationEventPublisher 实现进程内异步消息传递，配合 DB 持久化保证事件不丢失。主要服务于 Agent/Workflow Run 生命周期通知、Ingest 完成回调、长耗时工具回调，为模块间解耦和异步响应提供基础设施。

## 二、核心能力

1. **统一事件发布接口** — PlatformEventBus 封装 5 种事件类型的发布方法
2. **事件持久化** — 所有事件先写入 `platform_event` 表，保证不丢失
3. **异步消费** — Spring `@TransactionalEventListener(AFTER_COMMIT)` 确保主事务提交后再触发消费
4. **幂等消费** — 通过 `event.id` UUID 和 `status` 状态机防止重复处理
5. **状态跟踪** — PENDING → PROCESSED / FAILED，便于运维排查和重试

## 三、对外接口与数据契约

### Java 接口

```java
public interface PlatformEventBus {
    void publishRunStarted(String runId, String runType, String workspaceId);
    void publishRunFinished(String runId, String status, String workspaceId);
    void publishStepCompleted(String runId, String stepId, String stepName);
    void publishIngestJobCompleted(String jobId, String kbId, 
                                   String workspaceId, boolean success, 
                                   String errorMessage);
    void publishToolCallback(String runId, String toolCallId, 
                             String resultJson);
}
```

### 事件类型（一期 5 种）

| 事件类型 | 触发时机 | 典型消费方 |
|---------|---------|-----------|
| **RUN_STARTED** | Agent/Workflow Run 开始 | 监控告警、计费系统 |
| **RUN_FINISHED** | Run 结束（SUCCESS/FAILED） | 账单归档、性能统计 |
| **STEP_COMPLETED** | Workflow 单步执行完成 | 进度追踪、调试工具 |
| **INGEST_JOB_COMPLETED** | 知识库摄入任务完成 | 前端通知、webhook |
| **TOOL_CALLBACK_RECEIVED** | 长耗时工具回调结果到达 | Resume Workflow 执行 |

### DB 表结构

**platform_event**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | UUID，幂等键 |
| event_type | VARCHAR(64) | 事件类型枚举 |
| aggregate_id | VARCHAR(36) | run_id / job_id，聚合根 |
| workspace_id | VARCHAR(36) | 租户 ID（nullable） |
| payload_json | TEXT | 事件载荷 JSON |
| status | VARCHAR(16) | PENDING / PROCESSED / FAILED |
| created_at | DATETIME | 创建时间 |
| processed_at | DATETIME | 处理时间 |

索引：
- `idx_event_aggregate` (aggregate_id) — 查询某个 Run 的所有事件
- `idx_event_status_type` (status, event_type) — 重试队列扫描

## 四、生产环境真实调用链

### 场景：Agent Run 完整生命周期事件流

```
┌────────────────────────────────────────────────────────────┐
│ 用户发起 Agent Run                                          │
│ POST /api/v1/agent/runs                                    │
│ { "agentId": "doc_qa", "input": {"query": "..."} }        │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ① AgentService.start() — 开启数据库事务                     │
│    @Transactional                                          │
│    runId = "run-abc123"                                    │
│    INSERT agent_run (id, status=RUNNING, ...)             │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ② 发布 RUN_STARTED 事件                                     │
│    eventBus.publishRunStarted(runId, "AGENT", wsId)        │
│      → PlatformEventBusImpl.publishRunStarted()            │
│         - persist() 同步写 DB（在同一事务内）：              │
│           INSERT platform_event (                          │
│             id = UUID(),                                   │
│             event_type = 'RUN_STARTED',                    │
│             aggregate_id = 'run-abc123',                   │
│             workspace_id = 'ws-7',                         │
│             payload_json = '{"runId":"run-abc123",         │
│                              "runType":"AGENT"}',          │
│             status = 'PENDING',                            │
│             created_at = NOW()                             │
│           )                                                │
│         - publisher.publishEvent(                          │
│             new RunStartedEvent(runId, "AGENT", wsId)      │
│           ) ← Spring 内存事件，暂不触发                      │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ③ Agent 执行推理（略）                                       │
│    → LLM 调用、Tool 调用、Memory 操作                        │
│    → 执行成功，得到最终答案                                  │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ④ 发布 RUN_FINISHED 事件                                    │
│    eventBus.publishRunFinished(runId, "SUCCESS", wsId)     │
│      → persist() 同步写 DB：                                │
│        INSERT platform_event (                             │
│          event_type = 'RUN_FINISHED',                      │
│          aggregate_id = 'run-abc123',                      │
│          payload_json = '{"status":"SUCCESS"}',            │
│          status = 'PENDING'                                │
│        )                                                   │
│      → publisher.publishEvent(                             │
│          new RunFinishedEvent(runId, "SUCCESS", wsId)      │
│        )                                                   │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ⑤ AgentService 提交事务                                     │
│    COMMIT;                                                 │
│    → agent_run 表记录已落库                                 │
│    → platform_event 两条记录已落库（PENDING）                │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ⑥ Spring 事务后置监听器触发（异步）                          │
│    @TransactionalEventListener(AFTER_COMMIT)               │
│    @Async                                                  │
│    EventConsumer.onRunStarted(event)                       │
│      → 在独立线程池执行                                      │
│      → log.info("Run started: runId={}", event.runId())    │
│      → markProcessed(runId, RUN_STARTED):                  │
│         UPDATE platform_event                              │
│         SET status = 'PROCESSED',                          │
│             processed_at = NOW()                           │
│         WHERE aggregate_id = 'run-abc123'                  │
│           AND event_type = 'RUN_STARTED'                   │
│           AND status = 'PENDING'                           │
│         ORDER BY created_at DESC LIMIT 1                   │
└────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ⑦ EventConsumer.onRunFinished(event) 也触发                │
│      → log.info("Run finished: status={}", "SUCCESS")      │
│      → markProcessed(runId, RUN_FINISHED)                  │
│      → 后续扩展：通知计费系统、更新监控指标                    │
└────────────────────────────────────────────────────────────┘
```

### Ingest 完成回调场景

```
┌────────────────────────────────────────────────────────────┐
│ ① IngestPipeline 处理完最后一个 chunk                        │
│    jobId = "job-456"                                       │
│    INSERT rag_chunk (kb_id, doc_id, text, ...)            │
│    INSERT chunk_vector (chunk_id, embedding, ...)         │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ② IngestService.finishJob(jobId, success=true)            │
│    UPDATE ingest_job                                       │
│    SET status = 'COMPLETED', finished_at = NOW()           │
│    WHERE id = 'job-456'                                    │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ③ 发布 INGEST_JOB_COMPLETED 事件                           │
│    eventBus.publishIngestJobCompleted(                     │
│      jobId, kbId, wsId, success=true, errorMsg=null        │
│    )                                                       │
│      → persist() 写 DB：                                    │
│        INSERT platform_event (                             │
│          event_type = 'INGEST_JOB_COMPLETED',              │
│          aggregate_id = 'job-456',                         │
│          workspace_id = 'ws-7',                            │
│          payload_json = '{                                 │
│            "kbId":"kb-1",                                  │
│            "success":true,                                 │
│            "errorMessage":""                               │
│          }',                                               │
│          status = 'PENDING'                                │
│        )                                                   │
│      → publishEvent(IngestJobCompletedEvent)               │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ ④ 事务提交 → @Async 触发 EventConsumer.onIngestCompleted   │
│    → log.info("Ingest job completed: jobId={}", jobId)     │
│    → markProcessed(jobId, INGEST_JOB_COMPLETED)            │
│    → 后续扩展：                                             │
│       - WebSocket 推送前端："摄入完成，共 23 个 chunk"       │
│       - Webhook 通知外部系统                                │
│       - 更新知识库统计：KB.totalChunks += 23                │
└────────────────────────────────────────────────────────────┘
```

### 幂等消费保障

```
┌────────────────────────────────────────────────────────────┐
│ 场景：进程崩溃重启，事件表残留 PENDING 记录                   │
│ 启动时扫描：                                                │
│   SELECT * FROM platform_event                             │
│   WHERE status = 'PENDING'                                 │
│     AND created_at < NOW() - INTERVAL 5 MINUTE             │
│   ORDER BY created_at ASC LIMIT 100                        │
└──────────────────────────┬─────────────────────────────────┘
                           │
                           ▼
┌────────────────────────────────────────────────────────────┐
│ 对每条 PENDING 事件：                                        │
│   ① 检查 aggregate_id 对应的业务实体状态                     │
│      - Run 已结束？                                         │
│      - Job 已完成？                                         │
│   ② 如果业务已完成，直接 markProcessed                       │
│   ③ 如果业务未完成，根据策略：                               │
│      - 超时 > 30min：标记 FAILED                            │
│      - 否则：重新 publishEvent 触发消费                      │
└────────────────────────────────────────────────────────────┘
```

## 五、与其他子系统的协作

```
┌───────────────────────────────────────────────────────┐
│               业务模块（发布方）                         │
│  - AgentService                                        │
│  - WorkflowService                                     │
│  - IngestService                                       │
└──────────────────┬────────────────────────────────────┘
                   │ @Autowired PlatformEventBus
                   ▼
┌───────────────────────────────────────────────────────┐
│            EventBus（发布 + 持久化）                    │
│  - PlatformEventBusImpl                                │
│    ├─ PlatformEventMapper (写 DB)                      │
│    └─ ApplicationEventPublisher (内存传递)             │
└──────────────────┬────────────────────────────────────┘
                   │ publishEvent
                   ▼
┌───────────────────────────────────────────────────────┐
│            EventConsumer（消费方）                      │
│  - @TransactionalEventListener(AFTER_COMMIT)           │
│  - @Async 独立线程池                                    │
│  - markProcessed() 更新 status                         │
└──────────────────┬────────────────────────────────────┘
                   │ 后续扩展
                   ▼
┌───────────────────────────────────────────────────────┐
│            业务模块（消费方）                           │
│  - BillingService (计费)                               │
│  - NotificationService (前端通知)                       │
│  - MonitoringService (指标上报)                         │
└───────────────────────────────────────────────────────┘
```

## 六、关键设计权衡

1. **Spring ApplicationEventPublisher 而非 MQ** — 单体架构，进程内事件总线足够，避免引入 RabbitMQ / Kafka 的部署复杂度。扩展为分布式时可无缝迁移到 Spring Cloud Stream。

2. **发布时同步写 DB** — 保证事件不因进程崩溃丢失，DB 是持久化凭证。代价是 INSERT 增加 5-10ms 延迟，但对于异步事件可接受。

3. **AFTER_COMMIT 消费** — 确保主业务事务提交后再触发消费，避免消费成功但主事务回滚的一致性问题。代价是如果事务回滚，事件不会被消费（符合预期）。

4. **幂等由 event.id UUID 保证** — 每次 publish 生成新 UUID，消费者通过 `status = PENDING` 条件防止重复标记。代价是同一业务事件多次发布会产生多条 DB 记录（通过 aggregate_id 聚合查询）。

5. **消费者仅打 log + 标记状态** — 一期 EventConsumer 是空壳，具体响应逻辑由 Agent/Workflow 模块自行添加 `@EventListener`。降低耦合，方便后续扩展。

## 七、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **事务一致性** | 发布时 | @Transactional 保证事件与业务在同一事务 |
| **异步执行** | 消费时 | @Async + Spring 线程池（需在 Application 加 @EnableAsync） |
| **幂等消费** | markProcessed | WHERE status = PENDING 条件 + ORDER BY created_at LIMIT 1 |
| **多租户隔离** | 无 | workspace_id 仅记录不强制隔离（事件按 run_id / job_id 查询） |
| **追踪** | 无 | EventBus 本身不打 trace（发布方已记录 step） |

**注：** EventBus 不直接调用 Tracing，事件发布/消费的耗时由调用方负责记录。

## 八、未来扩展点

1. **重试队列** — 定时扫描 PENDING 超时事件（> 5min），自动重新发布或标记 FAILED。

2. **死信队列** — FAILED 事件保留 30 天，运维人工介入或重试。

3. **事件回放** — 根据 aggregate_id 查询所有事件，重建 Run / Job 状态（Event Sourcing）。

4. **外部 Webhook** — 支持 workspace 级别配置 webhook URL，自动推送指定事件类型到外部系统（如 Slack / 钉钉）。

5. **跨进程事件总线** — 迁移到 Spring Cloud Stream + Kafka，支持多实例部署和异地容灾。

6. **事件压缩归档** — PROCESSED 事件 > 90 天自动归档到冷存储（S3），减轻 DB 负担。
