# 项目能力总览与生产环境真实调用链

> 写于 2026-05-23。基于 docs/specs/ 下完整阅读后的总结。
> 配套阅读：架构 spec（2026-05-16-rag-agent-workflow-platform-design.md）、Plan A、B1-B4 spec。

## 一、项目定位

一个 **Java 单体 + 多模块** 的 RAG / Agent / Workflow 平台，自用 + 小团队，全 API 调用大模型（OpenAI / DeepSeek / Claude / 火山等）。覆盖三大核心能力：

1. **RAG 知识库问答** —— 文档摄入 → 多模态抽取 → 向量检索 → 重排
2. **Agent 运行时** —— ReAct 策略、流式输出、工具调用、状态机
3. **Workflow 编排** —— DAG 调度、9 种节点类型、Resume、嵌套 Agent

## 二、模块依赖

```
server  ──┬──> agent ────┐
          ├──> workflow ─┼──> platform-core ──> shared
          └──> rag ──────┘
cli ──> server (HTTP)
```

`platform-core` 是地基，提供 8 个子系统：**Auth / ModelGateway / ToolRegistry / MemoryStore / Tracing / SandboxRunner / EventBus / PromptHub**。

## 三、当前实施进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| **Plan A** | Foundation（Maven 多模块 + 多租户 + JWT/API Key 双通道 + Workspace 隔离 AOP + Docker + CI） | 已实现 |
| **B1** | Phase 0 依赖（resilience4j/pebble/janino/webflux）+ Phase 1 Tracing（trace_run / trace_step + 服务） | 已实现 |
| **B2** | PromptHub（Pebble 模板 + URI 解析 + 版本管理 + REST CRUD） | 已实现 |
| **B3** | MemoryStore（短期滑窗 + 工作 KV + 长期接口占位） | 已实现 |
| **B4** | ModelGateway（统一 chat/embed/rerank + Provider CRUD + AES 加密 + Resilience4j） | 已实现（待应用 b4-security-fixes） |
| **未来** | RAG / Agent / Workflow 三大业务模块 | 规划中 |

## 四、生产环境真实调用链

### 场景

用户提问"我们公司 Q1 财报数据是多少？同比增长多少？"

走的是一个标准的 **Agent ReAct 多轮对话** 链路，触发了几乎所有 Platform Core 子系统。

### 链路

```
┌─────────────────────────────────────────────────────────────────────────┐
│  外部入口                                                                │
│  POST /api/v1/agent/runs                                                │
│  Header: Authorization: Bearer eyJhbGc...                               │
│          X-Workspace-Id: 7                                              │
│  Body:   { "agentId": "doc_qa_agent", "input": {"question": "..."} }    │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ① Spring Security FilterChain                                           │
│    JwtAuthenticationFilter.doFilter()                                   │
│      → JwtTokenProvider.parse(token) → Claims{userId=42, type=ACCESS}   │
│      → SecurityContextHolder.set(auth)                                  │
│      → WorkspaceContextHolder.set(WorkspaceContext(7, 42, MEMBER))      │
│    通过                                                                  │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ② AgentController.start()                                               │
│    AgentService.start(agentId, input, ctx)                              │
│      → agentDefMapper.selectById  ← 自动注入 WHERE workspace_id = 7     │
│        (WorkspaceFilterInterceptor 通过 MyBatis-Plus 内拦截器)           │
│      → 创建 AgentRun, status=PENDING                                    │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ③ TracingService.startRun()                                             │
│    INSERT trace_run(id, workspace_id=7, type=AGENT, status=RUNNING)     │
│    runId = "run-abc123"                                                 │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ④ MemoryStore: 写入用户消息                                              │
│    shortTermMemory.append(runId, USER, "Q1财报数据是多少？")             │
│    INSERT memory_short_term(run_id, idx=0, role=USER, content=...)      │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ⑤ PromptHub: 渲染 system prompt                                          │
│    promptService.renderUri(7, "prompt://doc_qa/system@v3", vars)        │
│      → SELECT prompt WHERE ws=7 AND name='doc_qa/system' AND ver='v3'   │
│      → PromptRenderer (Pebble) 渲染 → "You are a helpful assistant..."  │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ⑥ ReAct 循环 step=1: LLM 推理 (Thought)                                  │
│    history = shortTermMemory.getWindow(runId, 20)                       │
│    modelGateway.chat(ChatRequest{                                       │
│      model: "openai/gpt-4o-mini",                                       │
│      messages: [system, history...],                                    │
│      tools: [retriever://kb_main, http://internal/search]               │
│    })                                                                   │
│      ├─→ ModelProviderService.getByName("openai")                       │
│      │     SELECT model_provider WHERE name='openai' AND enabled=true   │
│      ├─→ decryptApiKey(encrypted)  ← AES/GCM 解密                       │
│      ├─→ Resilience4j.Retry(maxAttempts=3) wraps:                       │
│      │     OpenAiCompatClient.chat(baseUrl, apiKey, req)                │
│      │       WebClient.post("https://api.openai.com/v1/chat/...")       │
│      └─→ TracingService.addStep(runId, "model:openai/gpt-4o-mini",      │
│            type=LLM, input, output, tokens=350, cost=0.0007, latencyMs) │
│                                                                         │
│    LLM 返回: { tool_calls: [{name:"retriever", args:{query:"Q1 财报"}}] }│
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ⑦ ReAct 循环 step=1: Tool 调用 (Action) — 走 RAG 检索                    │
│    ToolRegistry.invoke("retriever://kb_main", args)                     │
│      └─→ Retriever.retrieve(RetrieveQuery{text="Q1 财报", topK=20})      │
│           ├─→ modelGateway.embed("openai/text-embedding-3-small",       │
│           │                       ["Q1 财报"])                          │
│           │   → EmbeddingResponse{[0.12, -0.34, ...]}                   │
│           ├─→ pgvector HNSW 检索:                                       │
│           │   SELECT * FROM chunk_vector                                │
│           │   ORDER BY vector <=> :queryVec LIMIT 20                    │
│           ├─→ modelGateway.rerank("bge-reranker-v2-m3",                 │
│           │                       query, docs, topN=5)                  │
│           └─→ 返回 List<RetrievalResult>{chunkId, text, score, metadata}│
│                                                                         │
│    workingMemory.put(runId, "retrieved_chunks",                         │
│                       JSON([5 个 chunk]))                               │
│    INSERT memory_working(run_id, key=retrieved_chunks, value_json=...)  │
│    TracingService.addStep(runId, "retriever:kb_main", type=RETRIEVER)   │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ⑧ ReAct 循环 step=2: LLM 综合回答 (Observation → Thought → Final)       │
│    promptService.renderUri(7, "prompt://qa/synthesize@v2", {            │
│      question: "Q1 财报数据是多少？",                                    │
│      chunks: [...]                                                      │
│    })                                                                   │
│    modelGateway.chatStream(ChatRequest{...})  ← SSE 流式                │
│      │                                                                  │
│      └─→ Flux<String> 推送给客户端 SSE                                   │
│           GET /api/v1/agent/runs/run-abc123/stream                      │
│                                                                         │
│    LLM 返回: "Q1 营收 1 亿美元，利润 2000 万美元..."                     │
│                                                                         │
│    shortTermMemory.append(runId, ASSISTANT, finalAnswer)                │
│    INSERT memory_short_term(idx=1, role=ASSISTANT, content=...)         │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ⑨ 用户追问"同比增长多少？" (同一 runId, 多轮)                            │
│    shortTermMemory.append(runId, USER, "同比增长多少？")  // idx=2      │
│    history = shortTermMemory.getWindow(runId, 20)  // 拿到全部 3 条     │
│    ─ Agent 复用上轮检索结果（无需再 retrieve）                           │
│      cached = workingMemory.get(runId, "retrieved_chunks")              │
│    ─ LLM 生成 "同比增长 15%"                                             │
│    shortTermMemory.append(runId, ASSISTANT, "同比增长 15%")  // idx=3   │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ⑩ Run 结束                                                              │
│    TracingService.endRun(runId, SUCCESS)                                │
│    UPDATE trace_run SET status=SUCCESS, ended_at=NOW()                  │
│                                                                         │
│    EventBus.publish(RunFinished{runId, status, totalCost, totalTokens}) │
│    异步消费者：账单系统、监控告警、数据归档                                │
│                                                                         │
│    可选：shortTermMemory.clear(runId) / workingMemory.clear(runId)      │
│         （或保留供后续 audit / replay）                                  │
└─────────────────────────────────────────────────────────────────────────┘
```

## 五、链路上的横切关注点

| 切面 | 触发位置 | 实现 |
|------|---------|------|
| **认证** | 入口 ① | JWT filter → SecurityContext + WorkspaceContext |
| **多租户隔离** | 所有 SQL | MyBatis-Plus `WorkspaceFilterInterceptor` 自动注入 `WHERE workspace_id = ?` |
| **重试** | 所有 LLM/Embed/Rerank 调用 | Resilience4j Retry，幂等调用 3 次指数退避 |
| **追踪** | 每个 LLM/Tool/Retriever 调用 | TracingService.addStep（best-effort，不阻塞） |
| **加密** | API Key 落库 | AES/GCM 对称加密（按 b4-security-fixes 升级中） |
| **流式** | SSE endpoint | Reactor `Flux<String>` |
| **沙箱** | Workflow `code` 节点 | Janino + 白名单 + 5s 超时 |
| **配额** | Token / 步数上限 | RunContext 计数器，超限 FAIL_FAST |

## 六、数据落到哪些存储

| 存储 | 用途 | 示例表 / Key |
|------|------|-------------|
| **MySQL 8** | 业务主库 | workspace, user, trace_run, prompt, memory_*, model_provider |
| **PostgreSQL 16 + pgvector** | 向量检索 | chunk_vector（HNSW 索引） |
| **Redis 7** | 热缓存 / 限流 / 会话窗口 | `stm:run-abc:window:20` |
| **MinIO / S3** | 原始文档 + ingest artifact | `kb-001/raw/财报.pdf` |

## 七、部署形态

按架构 spec §8.3 强制约束：**全部 Docker 镜像交付**，宿主机锁死 JDK 8，镜像基底 `eclipse-temurin:17-jre`，不允许直接 `java -jar`。

```
┌─────────────────────────────────────────────────────┐
│ K8s Deployment (生产) / docker-compose (开发)        │
├─────────────────────────────────────────────────────┤
│  rag-agent-platform:0.1.0  (server fat jar)         │
│  ├─ depends_on:                                      │
│  │   ├─ mysql (3306)                                 │
│  │   ├─ postgres (5432) + pgvector                   │
│  │   ├─ redis (6379)                                 │
│  │   └─ minio (9000)                                 │
│  └─ env: OPENAI_API_KEY, DEEPSEEK_API_KEY,           │
│          MODEL_GATEWAY_ENCRYPTION_KEY (32+ chars)    │
└─────────────────────────────────────────────────────┘
```

应用层无状态（所有 state 在 MySQL/PG/Redis），可水平扩展。

## 八、关键设计权衡

- **单体而非微服务** —— 团队规模小，单体 + 多模块易开发易部署，业务边界清晰后再拆。
- **MySQL 业务 + PG 向量** —— MySQL 生态熟悉，pgvector HNSW 索引性能好，分工明确。
- **统一 OpenAI-Compatible 协议** —— OpenAI/DeepSeek/火山/通义都支持，一套客户端覆盖大部分场景，Claude 等少数 provider 单独适配。
- **Tracing best-effort** —— 落库失败不阻塞模型调用，用日志兜底，避免追踪系统拖垮主链路。
- **API Key 加密落库** —— AES/GCM 对称加密，密钥仅从环境变量读取，不入库不入日志。
- **流式不走 retry** —— 流式响应已开始往客户端推 token，重试会导致重复输出，仅记录失败由调用方处理。
- **Memory 按 run 隔离** —— `memory_*` 表不带 `workspace_id`，按 `run_id` 隔离，run 自身已绑 workspace，避免 tenant interceptor 干扰。