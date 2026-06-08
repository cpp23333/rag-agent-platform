# RAG 知识库检索功能分析与生产调用链

> 对应 spec：`docs/specs/c2-retriever.md`
> 写于 2026-06-08

## 一、定位与职责

Retriever 是 RAG 模块的检索引擎，负责根据用户查询从知识库中召回最相关的 chunk。核心链路为 `query → embed → pgvector HNSW 向量检索 → Rerank 重排 → topK 返回`。通过装饰器模式组合 VectorRetriever 和 RerankingRetriever，在保证召回率的同时提升精排准确度，为 Agent 提供高质量上下文。

## 二、核心能力

1. **向量检索（VectorRetriever）** — 调用 ModelGateway.embed 生成 query embedding，pgvector HNSW 索引快速 ANN 检索（< 50ms）
2. **Rerank 重排（RerankingRetriever）** — 装饰器包裹 VectorRetriever，调用 bge-reranker 对候选结果精排，topK 截断
3. **BM25 混合检索占位** — 接口预留，一期返回空列表（后续扩展 BM25 + 向量融合）
4. **知识库 CRUD** — KnowledgeBaseService 管理 KB 元数据（名称、embedding 模型、向量维度）
5. **检索选项配置** — RetrieveOptions 控制 rerank 开关、候选集大小、rerank 模型选择
6. **双数据源协同** — MySQL 查询 chunk 文本和元数据，PostgreSQL 查询向量相似度

## 三、对外接口与数据契约

### REST API

```java
// 检索 API
POST /api/v1/workspaces/{wsId}/knowledge-bases/{kbId}/retrieve
{
  "text": "Q1 财报数据",
  "topK": 5,
  "rerank": true,        // 默认开启
  "rerankModel": null    // null = 使用默认 bge-reranker-v2-m3
}

Response:
{
  "success": true,
  "data": [
    {
      "chunkId": "chunk-1",
      "docId": "doc-abc",
      "kbId": "kb-1",
      "text": "Q1 Revenue: $100M, up 15% YoY...",
      "score": 0.89,       // rerank score
      "metadata": {"page": 2}
    },
    ...
  ]
}

// KB 管理
POST /api/v1/workspaces/{wsId}/knowledge-bases
{
  "name": "财报知识库",
  "description": "2023-2024 财报",
  "embedModel": "openai/text-embedding-3-small",
  "vectorDim": 1536
}

GET /api/v1/workspaces/{wsId}/knowledge-bases       // 列表
GET /api/v1/workspaces/{wsId}/knowledge-bases/{kbId} // 详情
DELETE /api/v1/workspaces/{wsId}/knowledge-bases/{kbId}
```

### Java 接口

```java
public interface Retriever {
    List<RetrievalResult> retrieve(RetrieveQuery query);
}

public record RetrieveQuery(
    String kbId,
    String text,
    int topK,
    Map<String, Object> filters,    // 元数据过滤（一期未实现）
    RetrieveOptions options
);

public record RetrieveOptions(
    boolean rerank,          // 默认 true
    String rerankModel,      // null = 使用默认模型
    int vectorTopK           // rerank 前候选集大小，默认 20
);

public record RetrievalResult(
    String chunkId,
    String docId,
    String kbId,
    String text,
    double score,           // 向量相似度或 rerank score
    Map<String, Object> metadata
);
```

## 四、生产环境真实调用链

### 场景：Agent 多轮对话中的检索调用

```
┌──────────────────────────────────────────────────────────┐
│ 用户通过 Agent 提问："我们 Q1 财报数据是多少？"             │
│ Agent ReAct 循环 Step 1: LLM 推理                         │
│   → LLM 返回 tool_call:                                   │
│     { "name": "retriever://kb_main",                     │
│       "args": {"query": "Q1 财报数据"} }                  │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ① ToolRegistry.invoke("retriever://kb_main", args)       │
│    → RetrieverTool.execute(query="Q1 财报数据")           │
│    → kbService.retrieve(RetrieveQuery{                   │
│        kbId: "kb-main",                                  │
│        text: "Q1 财报数据",                               │
│        topK: 5,                                          │
│        options: RetrieveOptions(rerank=true, vectorTopK=20)│
│      })                                                  │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ② KnowledgeBaseServiceImpl.retrieve()                    │
│    → rerankingRetriever.retrieve(query)                  │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ③ RerankingRetriever: 调用底层 VectorRetriever           │
│    candidates = vectorRetriever.retrieve(query)          │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ④ VectorRetriever: Query Embedding                       │
│    modelGateway.embed(EmbeddingRequest{                  │
│      model: null,  // 使用 KB 配置的 embedModel           │
│      texts: ["Q1 财报数据"]                               │
│    })                                                    │
│      → 查询 KB 元数据：                                   │
│        SELECT embed_model FROM knowledge_base            │
│        WHERE id = 'kb-main'                              │
│        → "openai/text-embedding-3-small"                 │
│      → 调用 OpenAI API：                                  │
│        POST https://api.openai.com/v1/embeddings         │
│        { "model": "text-embedding-3-small",              │
│          "input": ["Q1 财报数据"] }                       │
│      → 返回 EmbeddingResponse{                           │
│          embeddings: [[0.08, -0.21, 0.43, ...]]  // 1536维│
│        }                                                 │
│    queryVec = [0.08, -0.21, 0.43, ...]                   │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ⑤ VectorRetriever: pgvector HNSW 检索                    │
│    pgJdbcTemplate.queryForList(                          │
│      "SELECT chunk_id, 1 - (embedding <=> ?) AS score    │
│       FROM chunk_vector                                  │
│       WHERE kb_id = ?                                    │
│       ORDER BY embedding <=> ?                           │
│       LIMIT ?",                                          │
│      new PGvector(queryVec), "kb-main",                  │
│      new PGvector(queryVec), 20                          │
│    )                                                     │
│      → pgvector operator <=> 计算余弦距离                 │
│      → HNSW 索引加速（近似最近邻）                         │
│      → 返回 20 个候选 chunk_id + score                    │
│        [                                                 │
│          {"chunk_id": "c1", "score": 0.78},              │
│          {"chunk_id": "c2", "score": 0.76},              │
│          ...                                             │
│          {"chunk_id": "c20", "score": 0.52}              │
│        ]                                                 │
│    执行耗时：23ms                                         │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ⑥ VectorRetriever: 从 MySQL 获取 chunk 文本              │
│    for each chunk_id in [c1, c2, ..., c20]:             │
│      RagChunk chunk = chunkMapper.selectById(chunk_id)   │
│        SELECT * FROM rag_chunk WHERE id = ?              │
│        → {                                               │
│          id: "c1",                                       │
│          kb_id: "kb-main",                               │
│          doc_id: "doc-abc",                              │
│          text: "Q1 Revenue: $100M, up 15% YoY...",       │
│          position: 3,                                    │
│          modality: "text",                               │
│          metadata_json: '{"page":2}'                     │
│        }                                                 │
│      results.add(RetrievalResult{                        │
│        chunkId: "c1",                                    │
│        docId: "doc-abc",                                 │
│        kbId: "kb-main",                                  │
│        text: "Q1 Revenue: $100M...",                     │
│        score: 0.78,  // 向量相似度                        │
│        metadata: {"page": 2}                             │
│      })                                                  │
│    return 20 个 RetrievalResult                          │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ⑦ RerankingRetriever: Rerank 精排                        │
│    texts = [c1.text, c2.text, ..., c20.text]            │
│    modelGateway.rerank(RerankRequest{                    │
│      model: null,  // 使用配置的 default_rerank           │
│      query: "Q1 财报数据",                                │
│      documents: texts,                                   │
│      topN: 5                                             │
│    })                                                    │
│      → 调用 bge-reranker-v2-m3 API                        │
│        POST https://api.volcengine.com/rerank            │
│        { "query": "Q1 财报数据",                          │
│          "documents": [20 个 text],                       │
│          "top_n": 5 }                                    │
│      → 返回 RerankResponse{                              │
│          model: "bge-reranker-v2-m3",                    │
│          results: [                                      │
│            {index: 0, score: 0.89, text: "Q1 Revenue..."}, │
│            {index: 5, score: 0.85, text: "Q1 Profit..."}, │
│            {index: 2, score: 0.82, text: "Q1 Growth..."}, │
│            {index: 11, score: 0.79, text: "Revenue breakdown..."}, │
│            {index: 7, score: 0.76, text: "Financial highlights..."}│
│          ]                                               │
│        }                                                 │
│    执行耗时：180ms                                        │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ⑧ RerankingRetriever: 重排结果映射回原 chunk             │
│    for each reranked in [0, 5, 2, 11, 7]:               │
│      original = candidates[reranked.index]               │
│      result.add(RetrievalResult{                         │
│        chunkId: original.chunkId,                        │
│        docId: original.docId,                            │
│        kbId: original.kbId,                              │
│        text: original.text,                              │
│        score: reranked.score,  // 替换为 rerank score     │
│        metadata: original.metadata                       │
│      })                                                  │
│    return top 5 results                                  │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ⑨ Agent 接收检索结果                                      │
│    workingMemory.put(runId, "retrieved_chunks",          │
│                      JSON([5 个 chunk]))                 │
│    INSERT memory_working (                               │
│      run_id, key='retrieved_chunks',                     │
│      value_json='[...]'                                  │
│    )                                                     │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ⑩ Agent ReAct Step 2: LLM 综合回答                        │
│    promptService.render("qa/synthesize", {               │
│      question: "Q1 财报数据是多少？",                      │
│      chunks: [5 个检索到的 chunk]                          │
│    })                                                    │
│    modelGateway.chat(ChatRequest{                        │
│      messages: [system, user + retrieved context]        │
│    })                                                    │
│    → LLM 返回: "根据财报，Q1 营收 1 亿美元，同比增长 15%"   │
└──────────────────────────────────────────────────────────┘
```

### 装饰器模式：RerankingRetriever 包裹 VectorRetriever

```
┌────────────────────────────────────────────────────────┐
│            RerankingRetriever (装饰器)                  │
│  ┌──────────────────────────────────────────────────┐  │
│  │ ① 调用 vectorRetriever.retrieve(query)            │  │
│  │    → 获得 20 个向量候选                            │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ ② 检查 options.rerank:                            │  │
│  │    if false:                                      │  │
│  │      return candidates.subList(0, topK)           │  │
│  │    if true:                                       │  │
│  │      continue to step 3                           │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ ③ modelGateway.rerank(query, texts, topN)        │  │
│  │    → 调用 bge-reranker API                         │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ ④ 重排结果映射回原 RetrievalResult                 │  │
│  │    更新 score 为 rerank score                      │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ ⑤ catch Exception (rerank 失败降级):              │  │
│  │    log.warn("Rerank failed, fallback")            │  │
│  │    return candidates.subList(0, topK)             │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  依赖: VectorRetriever vectorRetriever                 │
│        ModelGateway modelGateway                       │
└────────────────────────────────────────────────────────┘

                           │ 调用
                           ▼
┌────────────────────────────────────────────────────────┐
│              VectorRetriever (被装饰者)                 │
│  ┌──────────────────────────────────────────────────┐  │
│  │ ① Embed query                                    │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ ② pgvector HNSW 检索 vectorTopK 个候选            │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ ③ MySQL 查询 chunk 文本和元数据                   │  │
│  ├──────────────────────────────────────────────────┤  │
│  │ ④ 返回 List<RetrievalResult>                     │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  依赖: ModelGateway (embed)                            │
│        JdbcTemplate (pgJdbcTemplate)                   │
│        RagChunkMapper (MySQL)                          │
└────────────────────────────────────────────────────────┘
```

### Rerank 失败降级场景

```
┌──────────────────────────────────────────────────────────┐
│ ① VectorRetriever 返回 20 个候选                          │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ② RerankingRetriever 调用 modelGateway.rerank()          │
│    → HTTP 调用 bge-reranker API                           │
│    → 超时 / 503 Service Unavailable                       │
│    → 抛出 RuntimeException("rerank unavailable")          │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ③ RerankingRetriever catch 异常                          │
│    catch (Exception e) {                                 │
│      log.warn("Rerank failed, falling back: {}",         │
│                e.getMessage());                          │
│      return candidates.subList(0, topK);                 │
│    }                                                     │
│    → 直接返回向量检索的前 5 个结果（未重排）                │
└──────────────────────────┬───────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────┐
│ ④ Agent 正常接收结果                                      │
│    召回率略降（未经精排），但不阻塞检索流程                   │
└──────────────────────────────────────────────────────────┘
```

## 五、与其他子系统的协作

| 依赖方 | 用途 | 集成点 |
|--------|------|--------|
| **Agent/Workflow** | 执行检索 Tool | ToolRegistry → RetrieverTool → KnowledgeBaseService |
| **ModelGateway** | Query embedding + Rerank | VectorRetriever.embed / RerankingRetriever.rerank |
| **WorkingMemory** | 缓存检索结果 | workingMemory.put("retrieved_chunks", results) |
| **Tracing** | 记录检索耗时 | TracingService.addStep(type=RETRIEVER, latency, chunkCount) |
| **IngestPipeline** | 提供向量数据 | chunk_vector 表由 Ingest 写入 |

```
┌────────────────────────────────────────────────────┐
│              Agent / Workflow (调用方)              │
└──────────────────┬─────────────────────────────────┘
                   │ Tool 调用
                   ▼
┌────────────────────────────────────────────────────┐
│          KnowledgeBaseService (门面)                │
│  - create / get / list / delete KB                 │
│  - retrieve(query) → RerankingRetriever            │
└──────────────────┬─────────────────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────────────────┐
│           Retriever 层（检索核心）                   │
│  ┌──────────────────────────────────────────────┐  │
│  │ RerankingRetriever (装饰器)                   │  │
│  │   ├─→ VectorRetriever (被装饰者)               │  │
│  │   └─→ ModelGateway.rerank()                   │  │
│  ├──────────────────────────────────────────────┤  │
│  │ BM25Retriever (no-op 占位)                    │  │
│  └──────────────────────────────────────────────┘  │
└──────────────────┬─────────────────────────────────┘
                   │
                   ▼
┌────────────────────────────────────────────────────┐
│              数据层（双数据源）                      │
│  ┌──────────────────┐  ┌──────────────────────┐   │
│  │ MySQL            │  │ PostgreSQL + pgvector│   │
│  │ - rag_chunk      │  │ - chunk_vector       │   │
│  │ - knowledge_base │  │   (HNSW 索引)         │   │
│  └──────────────────┘  └──────────────────────┘   │
└────────────────────────────────────────────────────┘
```

## 六、关键设计权衡

1. **RerankingRetriever 是装饰器而非子类** — 职责分离：VectorRetriever 专注向量检索，RerankingRetriever 负责重排。两者独立测试，灵活组合（可选开启/关闭 rerank）。代价是多一层对象嵌套。

2. **Rerank 失败降级不抛异常** — Rerank 服务可能不稳定（外部 API 限流 / 超时），检索结果仍可用（纯向量结果）。保证系统可用性优于追求最优精度。

3. **pgvector 用独立 JdbcTemplate** — 主 DataSource 是 MySQL（业务数据），pgvector 必须走 PostgreSQL 连接。Spring Boot 多数据源配置（`@Qualifier("pgJdbcTemplate")`）。代价是需要手动管理两个数据源的事务。

4. **BM25 留 no-op 接口** — 不删除结构，方便后续实现 hybrid 检索（向量 + BM25 融合）。一期返回空列表，不影响主链路。

5. **向量检索返回 vectorTopK=20 候选** — Rerank 需要候选集足够大才能体现精排效果（topK=5 时，20 个候选覆盖 4 倍空间）。代价是 pgvector 查询略慢（20 vs 5），但 HNSW 索引下仍在 50ms 内。

6. **metadata 过滤一期不实现** — RetrieveQuery.filters 字段预留，但 pgvector 不支持混合过滤（需要先向量检索再 post-filter）。后续可迁移到 Milvus / Qdrant 支持原生 metadata 过滤。

## 七、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **多租户隔离** | MySQL 查询 | MyBatis-Plus 自动注入 WHERE workspace_id = ? |
| **向量隔离** | pgvector 查询 | WHERE kb_id = ?（手动拼接 SQL） |
| **重试** | ModelGateway | Resilience4j Retry（embed / rerank 失败重试 3 次） |
| **追踪** | 调用方 | Agent/Workflow 记录 RETRIEVER step 耗时（Retriever 本身不打 trace） |
| **降级** | Rerank 失败 | catch Exception 返回向量结果（不中断检索） |
| **缓存** | 未实现 | 后续可加 Redis 缓存热门 query embedding |

## 八、未来扩展点

1. **BM25 混合检索** — 实现 BM25Retriever，与向量结果按权重融合（RRF / Weighted Sum）。适合关键词精确匹配场景。

2. **Metadata 过滤** — 支持 RetrieveQuery.filters（如 `{"page": 2, "docType": "report"}`），pgvector 检索后 post-filter 或迁移到支持混合过滤的向量库。

3. **查询改写** — 用 LLM 扩展 query（同义词 / 拼写纠错 / 多语言翻译），提升召回率。

4. **分布式向量索引** — pgvector 单机 10M chunk 性能瓶颈，迁移到 Milvus / Qdrant 支持分片、副本、混合过滤。

5. **Embedding 缓存** — Redis 缓存高频 query embedding（TTL=1h），减少 embed API 调用次数（成本优化）。

6. **自适应 topK** — 根据 query 复杂度和 KB 规模动态调整 topK（简单问题 topK=3，复杂问题 topK=10）。

7. **检索质量评估** — 计算 MRR / NDCG 指标，A/B 测试不同 chunking / rerank 策略，持续优化检索效果。
