# RAG / Agent / Workflow 平台架构设计

- **状态**：Draft（待评审）
- **创建日期**：2026-05-16
- **范围**：平台架构 spec（不含子系统 detailed plan，后续每个 §3–§6 模块单独写实现 plan）
- **目标读者**：本人 + 未来加入的小团队
- **代码仓库**：`~/IdeaProjects/rag-agent-platform`（独立新仓，与 MetricsCatalog 无关）

## 0. 目标与非目标

### 0.1 目标
- 自用 + 小团队协作；保留未来对外服务可能性
- 提供三大能力：**RAG 知识库问答** / **Agent 运行时** / **Workflow 编排**
- 全 API 调用大模型（OpenAI / DeepSeek / Claude / 送宝 / 火山等）
- Java 技术栈（Spring Boot + Spring AI），开发者友好（YAML/JSON + CLI/API 优先）
- 数据规模：10 万 ~ 百万级 chunk

### 0.2 非目标（一期明确不做）
- 可视化 Workflow 编辑器
- Multi-Agent 协作
- Plan-Execute Agent 策略
- 模型自动路由（按任务类型/成本）
- Long-term memory（向量化记忆库）
- Human-in-the-loop workflow
- Distributed execution / 分布式调度
- Compensation / Saga
- 完整评测系统 UI（只留 CLI 入口）

---

## 1. 系统模块划分

```
rag-agent-platform/
├─ platform-core/        # 公共能力层：ModelGateway / ToolRegistry / MemoryStore /
│                         # Tracing / SandboxRunner / EventBus / PromptHub / Auth
├─ rag/                   # 知识库摄入 + 检索
├─ agent/                 # Agent 定义 + 运行时（ReAct）
├─ workflow/              # Workflow 定义 + DAG 调度
├─ server/                # Spring Boot 启动模块,REST API
├─ cli/                   # 命令行客户端（mp-cli）
└─ shared/                # 跨模块 DTO / 工具类
```

**依赖关系（单向）**：
```
server ──┬─> agent ──┐
         ├─> workflow ┼─> platform-core
         └─> rag ─────┘
cli ──> server (HTTP)
```

不允许反向依赖；platform-core 不依赖任何业务模块。

---

## 2. 数据模型（核心表）

> 完整 schema 见各模块实现 plan。下面列高层结构。

### 2.1 多租户
```
workspace(id, name, owner_id, created_at)
user(id, email, password_hash, ...)
workspace_member(workspace_id, user_id, role)
```
所有业务表强制带 `workspace_id`，Repository 层注入过滤条件。

### 2.2 Platform Core
```
model_provider(id, name, type, base_url, api_key_ref, default_model_map)
tool_def(id, workspace_id, name, impl_type, schema_json, config_json)
memory_short_term(run_id, idx, role, content, created_at)        -- 会话窗口
memory_working(run_id, key, value_json, updated_at)               -- Run 内 KV
trace_run(id, type[agent|workflow|ingest], status, parent_run_id, ...)
trace_step(id, run_id, name, input, output, tokens, cost, latency_ms)
prompt(id, name, version, template, variables_schema, created_at)
```

### 2.3 RAG
```
knowledge_base(id, workspace_id, name, embed_model, vector_dim, created_at)
doc(id, kb_id, source, content_hash, status, ingested_at)
chunk(id, kb_id, doc_id, text, metadata_json, position, modality)
chunk_vector(chunk_id, vector, model_version)                     -- pgvector
ingest_job(id, kb_id, status, progress, error, created_at)
```

### 2.4 Agent
```
agent_def(id, workspace_id, name, version, yaml, created_at)
agent_run(id, agent_def_id, workspace_id, status, input_json, output_json, started_at, ended_at)
agent_step(id, run_id, idx, thought, action_json, observation_json, tokens, cost, latency_ms)
```

### 2.5 Workflow
```
workflow_def(id, workspace_id, name, version, yaml, created_at)
workflow_run(id, workflow_def_id, workspace_id, status, input_json, output_json, started_at, ended_at)
node_run(id, run_id, node_id, status, attempt, input_json, output_json, error, started_at, ended_at)
```

### 2.6 选型
- **主库**：MySQL 8（业务表）
- **向量库**：PostgreSQL 16 + pgvector（HNSW 索引）
- **缓存**：Redis（会话窗口、限流、provider 健康状态）
- **对象存储**：MinIO / S3（原始文档、ingest artifact）

---

## 3. Platform Core

### 3.1 ModelGateway
统一 LLM / Embedding / Rerank / Vision 入口。

- **职责**：provider 路由、key 管理、retry（Resilience4j）、限流、调用计费、缓存
- **一期范围**：手动配置 default + fallback；不做自动路由
- **接口**：
  ```java
  ChatResponse chat(ChatRequest req);          // 流式 + 非流式
  Stream<ChatChunk> chatStream(ChatRequest req);
  EmbeddingResponse embed(EmbeddingRequest req);
  RerankResponse rerank(RerankRequest req);
  VisionResponse vision(VisionRequest req);    // PDF 图片 caption + OCR
  ```
- **配置示例**：
  ```yaml
  providers:
    openai:
      type: openai
      base_url: https://api.openai.com/v1
      api_key: ${OPENAI_API_KEY}
      models:
        chat: [gpt-4o, gpt-4o-mini]
        embedding: [text-embedding-3-small]
    deepseek:
      type: openai-compat
      base_url: https://api.deepseek.com
      api_key: ${DEEPSEEK_API_KEY}
      models:
        chat: [deepseek-chat, deepseek-coder]
  routing:
    default_chat: openai/gpt-4o-mini
    fallback_chat: deepseek/deepseek-chat
    default_embedding: openai/text-embedding-3-small
    default_rerank: bge/bge-reranker-v2-m3
  ```

### 3.2 ToolRegistry
所有 Tool 的注册中心，被 Agent / Workflow 共享。

- **Tool 接口**：
  ```java
  public interface Tool {
      String name();
      JsonSchema inputSchema();
      JsonSchema outputSchema();
      ToolResult invoke(ToolInput input, ToolContext ctx);
  }
  ```
- **impl_type**：
  - `JAVA_BEAN`：Spring Bean 实现，编译期注册
  - `HTTP`：通过 YAML 声明的外部 HTTP API
  - `WORKFLOW_REF`：包装一个 workflow 为 Tool
  - `RETRIEVER_REF`：包装一个 Retriever 为 Tool
- **执行模型**：同步调用；长耗时（>10s）转异步并由 EventBus 唤醒上层 Run

### 3.3 MemoryStore
- **short_term**：会话窗口（最近 N 条 message）；持久化到 MySQL，按 `run_id` 隔离
- **working**：Run 内 scratchpad，KV 结构；Run 结束随之归档
- **long_term**：留接口（`LongTermMemory`），一期不实现

### 3.4 Tracing
- **trace_run** + **trace_step** 双表
- 每个 LLM / Tool / Node 调用一条 step，记录 input/output/tokens/cost/latency
- 兼容 OpenTelemetry：通过 SDK 输出 OTLP，便于接 Langfuse / Jaeger（一期只写 DB，OTLP 留 hook）

### 3.5 SandboxRunner
Java 子集，**Janino** 实现。

- **白名单**：
  - 允许：`java.lang.*`（除 `Runtime`/`ProcessBuilder`/`System.exit`）、`java.util.*`、`java.time.*`、`java.math.*`、`java.text.*`、平台提供的 `SafeUtils`、`Map`/`List` 入参
  - 禁止：reflection、`java.io.*`、`java.net.*`、`java.nio.*`、自定义类加载
- **CompiledScript** 缓存（按 source 的 SHA-256）
- **超时**：单次执行硬上限 5s，超时杀线程
- **接口**：
  ```java
  Object run(String source, Map<String, Object> input, Duration timeout);
  ```

### 3.6 EventBus
Spring `ApplicationEventPublisher` + 持久化事件表（异步消费幂等）。事件类型：
- `RunStarted` / `RunFinished` / `StepCompleted`
- `IngestJobCompleted`
- `ToolCallbackReceived`（长耗时工具完成）

### 3.7 PromptHub
- 按 `name@version` 引用：`prompt://qa/synthesize@v2`
- 模板引擎：**Pebble**（语法简洁，沙箱安全）
- 支持变量 schema 校验（JSON Schema）

### 3.8 Auth & Workspace
- JWT（access 1h + refresh 30d）
- 角色：`OWNER` / `ADMIN` / `MEMBER` / `VIEWER`
- API key（机器调用）+ user token（人工）双通道
- Workspace 隔离强制在 Repository 层（AOP 注入 `workspace_id`）

---

## 4. RAG

### 4.1 Ingest 流水线
```
File/URL/Text
  → Loader (PDF/MD/HTML/DOCX/TXT)
  → Cleaner (去页眉页脚、HTML 标签、重复块)
  → Multi-modal 抽取:
       * 表格 → Markdown 表格 → 文本 chunk (modality=table)
       * 图片 → VLM(caption) + OCR → 文本 chunk (modality=image)
  → Chunker (Recursive 或 Markdown-aware)
  → Embedder (批量 ModelGateway.embed)
  → Persist (MySQL + pgvector)
```

- **幂等**：`doc(source, content_hash)` 唯一键
- **异步**：`ingest_job` 表 + Spring `@Async` 任务执行器
- **进度**：写入 `ingest_job.progress`，CLI/UI 轮询

### 4.2 Chunking
- **Recursive**：分隔符 `\n\n → \n → 。/?/!`，size=500，overlap=80
- **Markdown-aware**：保留 heading 路径作为 `metadata.heading_path`

### 4.3 检索
```
Query
  → ParallelRetrieve:
       VectorRetriever (pgvector HNSW, top_k=20)
       [BM25Retriever 一期关闭，留接口]
  → Fuse (RRF, 一期 vector-only 直接跳过)
  → Rerank (默认开，bge-reranker-v2-m3)
  → top_k=5 返回
```

### 4.4 接口
```java
public interface KnowledgeBase {
    String id();
    IngestJob ingest(IngestRequest req);
    void deleteDoc(String docId);
    DocStats stats();
}

public interface Retriever {
    List<RetrievalResult> retrieve(RetrieveQuery query);
}

public record RetrieveQuery(
    String text, int topK, Map<String, Object> filters, RetrieveOptions options
) {}

public record RetrievalResult(
    String chunkId, String text, double score, Map<String, Object> metadata
) {}
```

`Retriever` 自动注册为 ToolRegistry 中 `impl_type=RETRIEVER_REF` 的 Tool。

### 4.5 评测（CLI-only）
- `EvalDataset(query, expected_chunks, expected_answer)`
- `EvalRunner` 跑批 → `eval_run` 表（recall@k、MRR、faithfulness）
- 无 UI，仅 CLI 命令：`mp-cli eval run <dataset_id>`

---

## 5. Agent Runtime

### 5.1 Agent 定义（YAML）
```yaml
id: doc_qa_agent
name: 文档问答助手
version: 1
model: openai/gpt-4o-mini
system_prompt: prompt://doc_qa/system@v3
strategy: react             # 一期只支持 react
max_steps: 8
max_tokens_per_run: 50000
tools:
  - retriever://kb_main
  - http://internal_api/search
  - workflow://summarize_doc
memory:
  short_term: window:20
  working: scratchpad
output_schema:
  type: object
  properties:
    answer: { type: string }
    citations: { type: array }
```

### 5.2 ReAct 执行
```
loop (step < max_steps):
  thought, action = LLM.invoke(system, history, tools)
  if action == FINAL: return answer
  observation = ToolRegistry.invoke(action.tool, action.args)
  history.append(thought, action, observation)
```

### 5.3 状态机
`PENDING → RUNNING → (WAITING_TOOL ↔ RUNNING) → SUCCESS|FAILED|TIMEOUT|CANCELLED`

### 5.4 流式输出（一期必须）
- LLM 调用走 ModelGateway 的 stream API
- Run 级事件总线（基于 Reactor `Flux<RunEvent>`）
- HTTP SSE endpoint：`GET /api/v1/agent/runs/{id}/stream`

### 5.5 安全护栏
- Token 上限（按 Run）
- Tool 白名单（YAML 显式声明）
- Loop 检测：连续 3 步相同 action+args → 强制终止
- PII 过滤接口预留（一期不实现）

### 5.6 接口
```java
public interface AgentService {
    AgentRun start(String agentId, Map<String, Object> input, RunContext ctx);
    AgentRun get(String runId);
    Flux<RunEvent> stream(String runId);
    void cancel(String runId);
}
```

---

## 6. Workflow Engine

### 6.1 Workflow 定义（YAML）
```yaml
id: doc_qa_pipeline
version: 1
inputs:
  question: { type: string, required: true }
outputs:
  answer: { type: string }
  citations: { type: array }

nodes:
  - id: retrieve
    type: retriever
    config: { retriever_ref: kb_main, top_k: 5 }
    input: { text: "${inputs.question}" }

  - id: synthesize
    type: llm
    depends_on: [retrieve]
    config: { model: openai/gpt-4o-mini, prompt_ref: prompt://qa/synthesize@v2 }
    input:
      question: "${inputs.question}"
      chunks: "${retrieve.output}"

  - id: cite_check
    type: code
    depends_on: [synthesize]
    config:
      source: |
        return CiteChecker.validate(
          (String) input.get("answer"),
          (List) input.get("chunks")
        );
    input:
      answer: "${synthesize.output.text}"
      chunks: "${retrieve.output}"

outputs_map:
  answer: "${synthesize.output.text}"
  citations: "${cite_check.output.citations}"
```

### 6.2 节点类型（一期全做）
| Type        | 说明                                                    |
|-------------|-------------------------------------------------------|
| `llm`       | 调 ModelGateway                                          |
| `retriever` | 调 Retriever                                            |
| `tool`      | 调 ToolRegistry 中任意 Tool                                 |
| `http`      | 通用 HTTP（带模板渲染、超时、重试）                                    |
| `agent`     | 嵌套调用一个 Agent                                            |
| `branch`    | 条件分支（SpEL 表达式）                                          |
| `loop`      | 对数组做 map / foreach（fan-out 并发）                          |
| `code`      | Janino Java 子集（走 SandboxRunner）                         |
| `input` / `output` | 隐式入口 / 出口                                          |

### 6.3 调度
- 单进程内 **DAGScheduler**：拓扑排序 + 线程池（默认 8 并发）
- 节点状态：`PENDING → RUNNING → SUCCESS|FAILED|SKIPPED|RETRYING`
- 持久化：每个 `node_run` 单行
- 表达式：**SpEL**（Spring 自带，安全）

### 6.4 重试 & 错误处理
```yaml
- id: synthesize
  type: llm
  retry:
    max_attempts: 3
    backoff: exponential
    on: [TIMEOUT, RATE_LIMIT, SERVER_ERROR]
  on_error: skip | fail | fallback_node:cite_check
```
失败兜底走 **Resilience4j**。

### 6.5 Resume（一期必须）
- 所有节点要求**幂等**（部署时静态检查 + 文档约定）
- `WorkflowService.resume(runId)`：从最早 FAILED node 重新调度
- `node_run.attempt` 记录次数

### 6.6 嵌套约束
- Workflow ⇄ Agent 互相调用，**硬限制嵌套 3 层**
- 部署时静态分析依赖图（DFS 检测环 + 最大深度）
- 运行时 `RunContext.depth` 计数器，超限即 `FAIL_FAST`

### 6.7 接口 & CLI
```java
public interface WorkflowService {
    WorkflowRun run(String workflowId, Map<String, Object> input, RunContext ctx);
    WorkflowRun get(String runId);
    Flux<RunEvent> stream(String runId);
    void cancel(String runId);
    WorkflowRun resume(String runId);
}
```
CLI：
```
mp-cli wf deploy doc_qa_pipeline.yaml
mp-cli wf run doc_qa_pipeline --input '{"question":"..."}' --stream
mp-cli wf runs --status failed
mp-cli wf resume <run_id>
```

---

## 7. 技术选型汇总

| 维度            | 选型                                                |
|---------------|---------------------------------------------------|
| 语言 / 运行时       | Java 17（不复用 MetricsCatalog 的 Java 8 限制）           |
| Web 框架        | Spring Boot 3.x                                   |
| LLM SDK       | Spring AI + 自封装 ModelGateway                       |
| 主库            | MySQL 8 + Liquibase                               |
| 向量库          | PostgreSQL 16 + pgvector（HNSW）                    |
| 缓存            | Redis 7                                           |
| 对象存储          | MinIO（本地） / S3（云上）                                 |
| 模板引擎          | Pebble                                            |
| 表达式          | SpEL                                              |
| 沙箱            | Janino                                            |
| 重试 / 熔断       | Resilience4j                                      |
| 流式            | Spring WebFlux Reactor `Flux<RunEvent>` + SSE     |
| 异步任务          | Spring `@Async` + 任务表持久化                          |
| 测试            | JUnit 5 + AssertJ + Mockito + Testcontainers      |
| 构建            | Maven 多模块                                          |
| 部署            | Docker Compose（开发） / K8s（生产）                       |

> 选 Java 17 而不是 Java 8：Spring Boot 3 起步是 17；新项目无历史包袱；records / pattern matching 显著降低样板代码。

---

## 8. 部署形态

### 8.1 开发
```
docker-compose up
  ├─ mysql
  ├─ postgres (with pgvector)
  ├─ redis
  └─ minio
mvn spring-boot:run -pl server
```

### 8.2 生产
- 单实例：Spring Boot fat jar + systemd
- 多实例：K8s Deployment + Service；状态全在 MySQL/PG/Redis，应用层无状态

---

## 9. 安全 & 合规
- API key 加密存储（KMS / Vault；本地用环境变量 + 文件 ACL）
- 所有 LLM 请求/响应留痕（trace 表）
- workspace 强隔离（Repository AOP）
- 敏感日志脱敏（统一 logger 拦截器）
- Sandbox 白名单 + 超时

---

## 10. 不在本 spec 范围
本 spec 是平台架构，后续每个模块单独写 implementation plan：
- `plans/platform-core-impl.md`
- `plans/rag-impl.md`
- `plans/agent-impl.md`
- `plans/workflow-impl.md`
- `plans/server-cli-impl.md`

---

## 11. 决策日志（关键 trade-off）

| # | 决策                                       | 理由                                          | 拒绝方案                                |
|---|------------------------------------------|---------------------------------------------|-------------------------------------|
| 1 | Java 单体（非微服务）                            | 自用 + 小团队规模；运维简单                              | Python（生态好但团队 Java 强）/微服务（过早）       |
| 2 | pgvector 而非 Milvus/Qdrant                | 百万级足够；运维成本低；避免双写一致性                          | Milvus / Qdrant                     |
| 3 | Janino Java 子集而非 GraalJS                  | 全 Java 栈一致；白名单可控                            | GraalJS（多语言但部署复杂）                    |
| 4 | ReAct only（一期）                            | 简单可控；复杂任务交给 Workflow 兜底                      | Plan-Execute / ReWOO / Reflexion    |
| 5 | SSE 流式输出 P0                               | 用户体验关键；Reactor 自带                            | 仅轮询                                  |
| 6 | DAG 内单进程调度                                | YAGNI；单机够用                                    | Temporal / Camunda                  |
| 7 | YAML/CLI 优先 UX                            | 开发者为主；YAML 进 git 易 review                    | 可视化编辑器                              |
| 8 | 嵌套硬限制 3 层                                 | 防递归爆栈；静态可检测                                  | 不限制（运行时再说）                          |
| 9 | 一期不做 long-term memory                     | 价值未验证；复杂度高                                   | 一期带向量记忆库                            |
| 10 | 一期开 multi-modal（PDF 表格+图片）                | 真实文档含量高；不做后续要重做 ingest 流水线                  | 仅文本                                  |
| 11 | 一期关 BM25                                  | pgvector + rerank 对小规模够用；ES 运维重               | 一期就上 hybrid                          |
| 12 | 一期开 Rerank                                | 召回质量提升明显；bge-reranker 成本可控                   | 仅向量检索                               |
