# Tracing 子系统功能分析与生产调用链

> 对应 spec：`docs/specs/b1-phase0-dependencies.md`（Phase 1 部分）
> 写于 2026-06-08

## 一、定位与职责

Tracing 是 Platform Core 的首个子系统，为 Agent / Workflow / RAG 提供统一的调用追踪能力。它记录每次运行（Run）的生命周期及内部步骤（LLM 调用、工具执行、检索操作），落库供后续分析、成本核算、性能优化、审计查询使用。

## 二、核心能力

1. **Run 生命周期管理** — 创建 Run、标记状态（RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED）、记录开始和结束时间
2. **Step 记录** — 捕获每个 LLM 调用、Tool 执行、Retriever 查询的输入输出、token 消耗、延迟、成本
3. **多租户隔离** — Run 按 `workspace_id` 隔离，Step 通过 `run_id` 关联
4. **父子 Run 关系** — 支持嵌套追踪（如 Workflow 包含多个 Agent Run）
5. **Best-effort 记录** — 追踪失败不阻塞业务调用，降级到日志
6. **查询能力** — 按 Run ID 查询完整链路，按 workspace 查询历史 Run

## 三、对外接口与数据契约

### 核心接口

```java
package io.kyligence.ragagent.core.tracing;

public interface TracingService {
    TraceRun startRun(String workspaceId, RunType type, 
                      String parentRunId, String metadataJson);
    TraceRun endRun(String runId, RunStatus status);
    TraceStep addStep(String runId, String name, StepType type,
                      String input, String output, Integer tokens,
                      BigDecimal cost, Long latencyMs);
    TraceRun getRun(String runId);
    List<TraceStep> getSteps(String runId);
}
```

### 数据表

**trace_run** 表：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | Run UUID |
| workspace_id | VARCHAR(36) | 租户隔离 |
| type | VARCHAR(20) | AGENT/WORKFLOW/INGEST/EVAL |
| status | VARCHAR(20) | RUNNING/SUCCESS/FAILED/TIMEOUT/CANCELLED |
| parent_run_id | VARCHAR(36) | 嵌套 Run 的父 ID |
| metadata_json | TEXT | 扩展元数据（Agent 配置、Workflow 名称等） |
| started_at | DATETIME | 开始时间 |
| ended_at | DATETIME | 结束时间 |

**trace_step** 表：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | Step UUID |
| run_id | VARCHAR(36) | 所属 Run |
| name | VARCHAR(128) | 步骤名称（如 model:openai/gpt-4o-mini） |
| type | VARCHAR(20) | LLM/TOOL/NODE/RETRIEVER/CODE/RERANK |
| input | LONGTEXT | 输入内容（截断到 2000 字符） |
| output | LONGTEXT | 输出内容 |
| tokens | INT | token 消耗 |
| cost | DECIMAL(10,6) | 成本（美元） |
| latency_ms | BIGINT | 延迟（毫秒） |
| created_at | DATETIME | 创建时间 |

## 四、生产环境真实调用链

### 场景：Agent ReAct 多轮对话中的追踪链路

```
┌─────────────────────────────────────────────────────────────┐
│ 用户发起 Agent 对话请求                                       │
│ POST /api/v1/agent/runs                                      │
│ Body: { "agentId": "doc_qa", "input": "Q1财报数据是多少？" } │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ ① AgentService.start()                                       │
│    → TracingService.startRun(                                │
│         workspaceId = "7",                                   │
│         type = AGENT,                                        │
│         parentRunId = null,                                  │
│         metadataJson = '{"agentId":"doc_qa"}'                │
│      )                                                       │
│                                                              │
│    SQL:                                                      │
│    INSERT INTO trace_run (                                   │
│      id, workspace_id, type, status, metadata_json,          │
│      started_at                                              │
│    ) VALUES (                                                │
│      'run-abc123', '7', 'AGENT', 'RUNNING',                  │
│      '{"agentId":"doc_qa"}', NOW()                           │
│    );                                                        │
│                                                              │
│    返回: TraceRun { id='run-abc123', status=RUNNING }        │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ ② ReAct Loop - Step 1: LLM 推理                              │
│    ModelGateway.chat(ChatRequest{                            │
│      model: "openai/gpt-4o-mini",                            │
│      messages: [system, user_question]                       │
│    })                                                        │
│    ├─ 调用 OpenAI API (270ms, 350 tokens, $0.0007)          │
│    └─ 返回: tool_calls: [{name:"retriever", args:{...}}]     │
│                                                              │
│    → TracingService.addStep(                                 │
│         runId = "run-abc123",                                │
│         name = "model:openai/gpt-4o-mini",                   │
│         type = LLM,                                          │
│         input = "[{role:system,content:...},{role:user...}]",│
│         output = "{tool_calls:[{name:retriever...}]}",       │
│         tokens = 350,                                        │
│         cost = 0.0007,                                       │
│         latencyMs = 270                                      │
│      )                                                       │
│                                                              │
│    SQL:                                                      │
│    INSERT INTO trace_step (                                  │
│      id, run_id, name, type, input, output, tokens,          │
│      cost, latency_ms, created_at                            │
│    ) VALUES (                                                │
│      UUID(), 'run-abc123', 'model:openai/gpt-4o-mini',       │
│      'LLM', '[{role:system...', '{tool_calls:...', 350,      │
│      0.0007, 270, NOW()                                      │
│    );                                                        │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ ③ ReAct Loop - Step 2: 工具调用                              │
│    ToolRegistry.invoke("retriever", {...})                   │
│    ├─ Retriever.retrieve() (180ms)                           │
│    └─ 返回 5 个相关 chunks                                    │
│                                                              │
│    → TracingService.addStep(                                 │
│         runId = "run-abc123",                                │
│         name = "retriever:kb_main",                          │
│         type = RETRIEVER,                                    │
│         input = '{"query":"Q1财报","topK":20}',              │
│         output = '[{chunkId:c1,score:0.95,...}]',            │
│         tokens = null,                                       │
│         cost = null,                                         │
│         latencyMs = 180                                      │
│      )                                                       │
│                                                              │
│    SQL:                                                      │
│    INSERT INTO trace_step (...) VALUES (                     │
│      UUID(), 'run-abc123', 'retriever:kb_main', 'RETRIEVER', │
│      '{"query":"Q1财报"...', '[{chunkId:c1...', NULL,        │
│      NULL, 180, NOW()                                        │
│    );                                                        │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ ④ ReAct Loop - Step 3: LLM 综合回答                          │
│    ModelGateway.chat(ChatRequest{                            │
│      messages: [system, user, tool_result, ...]              │
│    })                                                        │
│    ├─ 调用 OpenAI API (320ms, 480 tokens, $0.0009)          │
│    └─ 返回: "Q1营收1亿美元，利润2000万美元..."                │
│                                                              │
│    → TracingService.addStep(                                 │
│         runId = "run-abc123",                                │
│         name = "model:openai/gpt-4o-mini",                   │
│         type = LLM,                                          │
│         input = "[...,{role:tool,content:[5 chunks]}]",      │
│         output = "Q1营收1亿美元...",                          │
│         tokens = 480,                                        │
│         cost = 0.0009,                                       │
│         latencyMs = 320                                      │
│      )                                                       │
│                                                              │
│    SQL:                                                      │
│    INSERT INTO trace_step (...) VALUES (                     │
│      UUID(), 'run-abc123', 'model:openai/gpt-4o-mini',       │
│      'LLM', '[...,{role:tool...', 'Q1营收1亿美元...', 480,   │
│      0.0009, 320, NOW()                                      │
│    );                                                        │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│ ⑤ Agent 结束                                                 │
│    → TracingService.endRun(                                  │
│         runId = "run-abc123",                                │
│         status = SUCCESS                                     │
│      )                                                       │
│                                                              │
│    SQL:                                                      │
│    UPDATE trace_run                                          │
│    SET status = 'SUCCESS', ended_at = NOW()                  │
│    WHERE id = 'run-abc123';                                  │
└─────────────────────────────────────────────────────────────┘
```

**调用链数据汇总**：
- 1 个 Run（run-abc123，总耗时 770ms）
- 3 个 Steps：2 次 LLM（830 tokens，$0.0016）+ 1 次 Retriever（180ms）

## 五、与其他子系统的协作

```
┌───────────────────────────────────────────────┐
│       Agent / Workflow / RAG 业务模块          │
│  (调用方，生成 Run 和 Step 事件)               │
└───────────────┬───────────────────────────────┘
                │ @Autowired
                ▼
┌───────────────────────────────────────────────┐
│          TracingService                       │
│  - startRun() / endRun()                      │
│  - addStep()                                  │
└───────────────┬───────────────────────────────┘
                │ MyBatis-Plus
                ▼
┌───────────────────────────────────────────────┐
│       MySQL 8 (trace_run / trace_step)        │
│  - trace_run: workspace_id 索引               │
│  - trace_step: run_id 索引                    │
└───────────────────────────────────────────────┘

关键集成点：
1. ModelGateway → TracingService.addStep(type=LLM)
2. ToolRegistry → TracingService.addStep(type=TOOL)
3. Retriever → TracingService.addStep(type=RETRIEVER)
```

**依赖关系**：
- **被依赖**：ModelGateway、ToolRegistry、MemoryStore、Workflow 节点执行器
- **依赖**：MyBatis-Plus、MySQL、WorkspaceAware 租户拦截器

## 六、关键设计权衡

1. **Best-effort 而非强一致** — 追踪失败不阻塞业务调用，用 try-catch 包裹 `addStep()`，异常降级到 log.warn。生产环境模型调用成功率 > 追踪完整性。

2. **Step 不绑 workspace_id** — `trace_step` 表不带 `workspace_id` 字段，通过 `run_id` 关联。因为 Run 已绑定 workspace，避免 MyBatis-Plus `WorkspaceFilterInterceptor` 在 join 查询时误拦截 Step。

3. **input/output 截断到 2000 字符** — 避免超长 prompt 或 response 撑爆 LONGTEXT，调用方通过 `truncate()` 主动截断。完整数据可选存 S3/MinIO。

4. **metadata_json 扩展字段** — Run 的 `metadata_json` 用于存储业务自定义字段（如 Agent 配置、Workflow 版本），避免频繁改表。

5. **索引策略** — `(workspace_id, type, status)` 复合索引支持"查某 workspace 的所有 AGENT 失败 Run"这类查询；`(run_id, created_at)` 支持按时间排序查 Step。

## 七、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **多租户隔离** | `trace_run` 表的所有查询 | `TraceRunMapper` 标注 `@WorkspaceAware`，MyBatis-Plus 拦截器自动注入 `WHERE workspace_id = ?` |
| **事务** | `startRun` / `endRun` / `addStep` | 标注 `@Transactional`，保证 Run 状态变更和 Step 插入的原子性 |
| **幂等性** | `addStep` 重复调用 | 无幂等保证，每次调用生成新 Step UUID；调用方需避免重复调用 |
| **性能优化** | 高并发写入 | 一期同步写 MySQL；二期可改为异步批量落库（Spring Batch / 消息队列） |

## 八、未来扩展点

1. **异步批量落库** — 当前同步写 MySQL，高并发场景可能成为瓶颈。可引入 Disruptor 或 Kafka，异步批量 flush。

2. **长期存储归档** — 超过 30 天的 Run/Step 归档到 S3/OSS，MySQL 只保留热数据，减少表大小。

3. **Trace 可视化** — 前端集成 Jaeger / Zipkin 风格的链路图，按时间轴展示 Step 瀑布流。

4. **成本分析聚合** — 定时 job 统计每个 workspace 的日/周/月 token 消耗和成本，输出到 `trace_cost_summary` 表。

5. **分布式追踪集成** — 如果未来拆分微服务，可集成 OpenTelemetry，将 `run_id` 映射为 `trace_id`，`step_id` 映射为 `span_id`。
