# RAG 知识库摄入流水线功能分析与生产调用链

> 对应 spec：`docs/specs/c1-ingest-pipeline.md`
> 写于 2026-06-08

## 一、定位与职责

Ingest Pipeline 是 RAG 模块的文档摄入流水线，负责将多格式文档（PDF / Markdown / TXT / DOCX / HTML / URL）转换为知识库可检索的向量 chunk。完整链路覆盖：文件上传 → 格式解析 → 文本清洗 → 多模态抽取（图片/表格 caption）→ 智能分块 → 向量化 → 双库落地（MySQL + pgvector），为后续检索提供高质量数据基础。

## 二、核心能力

1. **多格式 Loader** — PDF (Apache PDFBox) / Markdown / TXT / DOCX (Apache POI) / HTML (Jsoup) / URL 爬取
2. **文本清洗** — 去除重复空白、规范化换行、过滤空白段落
3. **多模态抽取** — PDF 图片/表格通过 ModelGateway.vision 生成文本描述
4. **智能分块** — RecursiveChunker（通用场景）/ MarkdownAwareChunker（保留标题层级）
5. **向量化** — 调用 ModelGateway.embed 生成 embedding，支持多种 embedding 模型
6. **双库持久化** — MySQL 存储文档元数据和 chunk 文本，PostgreSQL + pgvector 存储向量索引
7. **异步任务** — IngestJob 状态机跟踪进度（PENDING → PROCESSING → COMPLETED / FAILED）
8. **幂等防重** — 按 (kb_id, content_hash) 唯一约束防止同一文件重复摄入

## 三、对外接口与数据契约

### REST API

```java
// 上传文件摄入
POST /api/v1/workspaces/{wsId}/knowledge-bases/{kbId}/ingest/file
Content-Type: multipart/form-data
  - file: <binary>
  - sourceType: PDF | MARKDOWN | DOCX | HTML

Response: { "jobId", "docId", "chunkCount" }

// URL 爬取摄入
POST /api/v1/workspaces/{wsId}/knowledge-bases/{kbId}/ingest/url
{ "url": "https://..." }

// 查询任务状态
GET /api/v1/workspaces/{wsId}/knowledge-bases/{kbId}/ingest/jobs/{jobId}
Response: { "status": "COMPLETED", "progress": 100, "totalDocs": 1 }
```

### 关键实体

**KnowledgeBase** (MySQL)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | KB ID |
| workspace_id | BIGINT | 租户隔离 |
| name | VARCHAR(128) | KB 名称 |
| embed_model | VARCHAR(128) | 向量化模型（如 openai/text-embedding-3-small） |
| vector_dim | INT | 向量维度（1536 / 768 / ...） |

**RagDoc** (MySQL)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | 文档 ID |
| kb_id | VARCHAR(36) | 所属 KB |
| source | VARCHAR(1024) | 文件名或 URL |
| content_hash | VARCHAR(64) | SHA-256，幂等键 |
| status | VARCHAR(16) | INDEXED / FAILED |
| chunk_count | INT | 拆分的 chunk 数量 |

**RagChunk** (MySQL)

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | Chunk ID |
| kb_id | VARCHAR(36) | 所属 KB |
| doc_id | VARCHAR(36) | 所属文档 |
| text | LONGTEXT | chunk 文本内容 |
| position | INT | 在文档中的位置序号 |
| modality | VARCHAR(16) | text / image（vision caption） |
| metadata_json | TEXT | 扩展元数据（页码、标题路径等） |

**chunk_vector** (PostgreSQL + pgvector)

| 字段 | 类型 | 说明 |
|------|------|------|
| chunk_id | VARCHAR(36) | 关联 RagChunk.id |
| kb_id | VARCHAR(36) | 所属 KB |
| embedding | vector(1536) | pgvector 向量类型 |
| model_version | VARCHAR(64) | 向量化模型版本 |

索引：`idx_cv_hnsw ON embedding USING hnsw (vector_cosine_ops) WITH (m=16, ef_construction=64)`

## 四、生产环境真实调用链

### 场景：上传 PDF（含图表）完整摄入流程

```
┌─────────────────────────────────────────────────────────┐
│ 用户上传 Q1_Report.pdf (3 页，含 2 张图表)                │
│ POST /api/v1/.../ingest/file                            │
│   file: <binary 580KB>                                  │
│   sourceType: PDF                                       │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ① IngestController.ingestFile()                         │
│    → IngestService.ingest(wsId, IngestRequest{          │
│        kbId: "kb-1",                                    │
│        source: "Q1_Report.pdf",                         │
│        sourceType: "PDF",                               │
│        content: byte[580KB]                             │
│      })                                                 │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ② IngestService 创建 Job                                │
│    contentHash = sha256(content) = "e4d909..."          │
│    检查重复：                                            │
│      SELECT * FROM rag_doc                              │
│      WHERE kb_id = 'kb-1'                               │
│        AND content_hash = 'e4d909...'                   │
│      → NULL（首次摄入）                                  │
│    INSERT ingest_job (                                  │
│      id = "job-789",                                    │
│      kb_id = 'kb-1',                                    │
│      workspace_id = 7,                                  │
│      status = 'PENDING',                                │
│      total_docs = 1                                     │
│    )                                                    │
│    返回 IngestResult("job-789", null, 0)                │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ③ @Async IngestPipeline.process(job, request)          │
│    UPDATE ingest_job SET status = 'PROCESSING'          │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ④ Loader: PdfLoader.load(request)                      │
│    PDDocument.load(pdfBytes)                            │
│    for page 1..3:                                       │
│      PDFTextStripper.getText(page)                      │
│      segments.add(RawSegment{                           │
│        text: "Q1 Revenue: $100M...",                    │
│        modality: "text",                                │
│        metadata: {"page": 1}                            │
│      })                                                 │
│    return RawDocument{                                  │
│      source: "Q1_Report.pdf",                           │
│      contentHash: "e4d909...",                          │
│      segments: [seg1, seg2, seg3]  // 3 页文本          │
│    }                                                    │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ⑤ Cleaner: DocumentCleaner.clean(rawDoc)               │
│    for each segment:                                    │
│      text = REPEATED_BLANK.replaceAll(" ")              │
│      text = REPEATED_NEWLINE.replaceAll("\n\n")         │
│      filter blank segments                              │
│    return cleaned RawDocument (3 segments)              │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ⑥ Multi-modal: MultiModalExtractor.extractFromPdf()    │
│    PDFRenderer.renderImageWithDPI(page, 150)            │
│    for page 1..3:                                       │
│      render page → PNG image (base64)                   │
│      ModelGateway.chat(ChatRequest{                     │
│        model: "gpt-4o-mini",                            │
│        messages: [                                      │
│          "This is page 1. If it contains tables/       │
│           figures, describe them. Otherwise empty."     │
│          + "[IMAGE_BASE64:...]"                         │
│        ]                                                │
│      })                                                 │
│      → LLM 返回:                                         │
│         page 1: "" (纯文本页)                            │
│         page 2: "Bar chart showing Q1 revenue by        │
│                  region: NA $40M, EU $35M, APAC $25M"   │
│         page 3: "Table with 5 rows: Product, Units,    │
│                  Revenue..."                            │
│    return extraSegments: [                              │
│      RawSegment{                                        │
│        text: "Bar chart showing...",                    │
│        modality: "image",                               │
│        metadata: {"page": 2, "type": "vlm_caption"}     │
│      },                                                 │
│      RawSegment{                                        │
│        text: "Table with 5 rows...",                    │
│        modality: "image",                               │
│        metadata: {"page": 3, "type": "vlm_caption"}     │
│      }                                                  │
│    ]                                                    │
│    append to RawDocument → 5 segments total             │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ⑦ Chunker: RecursiveChunker.chunk()                    │
│    chunkSize = 500, overlap = 80                        │
│    for each segment (5 个):                             │
│      if text.length > 500:                              │
│        split by "\n\n" → "\n" → ". " → hard split       │
│      chunks.add(RawChunk{                               │
│        text: "Q1 Revenue: $100M. Our growth...",        │
│        position: 0,                                     │
│        modality: "text",                                │
│        metadata: {"page": 1}                            │
│      })                                                 │
│    共生成 8 个 chunks（3 页文本拆成 6 个 + 2 个图表）      │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ⑧ Embedder: ModelGateway.embed()                       │
│    texts = [chunk0.text, chunk1.text, ..., chunk7.text]│
│    EmbeddingRequest(model="openai/text-embedding-3-small",│
│                     texts)                              │
│    → OpenAI API:                                        │
│      POST https://api.openai.com/v1/embeddings          │
│      { "model": "text-embedding-3-small",               │
│        "input": [8 个 chunk 文本] }                      │
│    → 返回 EmbeddingResponse{                            │
│        embeddings: [                                    │
│          [0.12, -0.34, 0.56, ...], // 1536 维            │
│          [0.08, -0.21, 0.43, ...],                      │
│          ...                                            │
│        ]                                                │
│      }                                                  │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ⑨ Persist: 双库落地                                     │
│    @Transactional                                       │
│    MySQL:                                               │
│      INSERT rag_doc (                                   │
│        id = "doc-abc",                                  │
│        kb_id = 'kb-1',                                  │
│        source = 'Q1_Report.pdf',                        │
│        content_hash = 'e4d909...',                      │
│        status = 'INDEXED',                              │
│        chunk_count = 8                                  │
│      )                                                  │
│      for i in 0..7:                                     │
│        INSERT rag_chunk (                               │
│          id = "chunk-{i}",                              │
│          kb_id = 'kb-1',                                │
│          doc_id = 'doc-abc',                            │
│          text = chunks[i].text,                         │
│          position = i,                                  │
│          modality = chunks[i].modality,                 │
│          metadata_json = '{"page":1}'                   │
│        )                                                │
│    PostgreSQL:                                          │
│      for i in 0..7:                                     │
│        INSERT INTO chunk_vector (                       │
│          chunk_id, kb_id, embedding, model_version      │
│        ) VALUES (                                       │
│          'chunk-{i}', 'kb-1',                           │
│          '[0.12,-0.34,...]'::vector(1536),              │
│          'openai/text-embedding-3-small'                │
│        )                                                │
│    pgvector HNSW 索引自动更新                            │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ⑩ IngestJob 状态机迁移                                  │
│    UPDATE ingest_job                                    │
│    SET status = 'COMPLETED',                            │
│        progress = 100,                                  │
│        finished_at = NOW()                              │
│    WHERE id = 'job-789'                                 │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ⑪ EventBus 发布事件                                     │
│    eventBus.publishIngestJobCompleted(                  │
│      jobId = "job-789",                                 │
│      kbId = "kb-1",                                     │
│      workspaceId = 7,                                   │
│      success = true,                                    │
│      errorMessage = null                                │
│    )                                                    │
│    → INSERT platform_event (                            │
│        event_type = 'INGEST_JOB_COMPLETED',             │
│        aggregate_id = 'job-789',                        │
│        status = 'PENDING'                               │
│      )                                                  │
│    → @Async 消费者通知前端 / Webhook                     │
└─────────────────────────────────────────────────────────┘
```

### 失败场景：PDF 解析失败

```
┌─────────────────────────────────────────────────────────┐
│ ① PdfLoader.load() 抛出异常                              │
│    → PDDocument.load(corrupted bytes)                   │
│    → IOException: "PDF header signature not found"      │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ② IngestPipeline catch 异常                             │
│    UPDATE ingest_job                                    │
│    SET status = 'FAILED',                               │
│        error_message = 'Failed to parse PDF: ...',      │
│        finished_at = NOW()                              │
│    WHERE id = 'job-789'                                 │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────┐
│ ③ EventBus 发布失败事件                                  │
│    publishIngestJobCompleted(                           │
│      jobId, kbId, wsId, success=false,                  │
│      errorMessage="Failed to parse PDF"                 │
│    )                                                    │
│    → 前端轮询 GET /jobs/{jobId} 得知失败                  │
│    → 展示错误信息，提示用户重新上传                        │
└─────────────────────────────────────────────────────────┘
```

## 五、与其他子系统的协作

```
┌────────────────────────────────────────────────────┐
│                   REST Layer                        │
│  IngestController + KnowledgeBaseController         │
└──────────────────────┬─────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────┐
│                  Service Layer                      │
│  IngestService + KnowledgeBaseService               │
└──────────────────────┬─────────────────────────────┘
                       │
                       ▼
┌────────────────────────────────────────────────────┐
│              Ingest Pipeline (核心)                 │
│  Loader → Cleaner → MultiModal → Chunker → Embedder│
└──────┬─────────────┬────────────────┬──────────────┘
       │             │                │
       ▼             ▼                ▼
┌──────────┐  ┌──────────────┐  ┌─────────────┐
│ModelGateway│  │ EventBus    │  │  DB Persist │
│  (vision  │  │ (通知前端)   │  │ MySQL + PG  │
│   embed)  │  └──────────────┘  └─────────────┘
└──────────┘
```

依赖关系：
- **ModelGateway** — 调用 LLM vision caption、embed API
- **EventBus** — 发布 INGEST_JOB_COMPLETED 事件
- **WorkspaceContext** — 多租户隔离（自动注入 WHERE workspace_id = ?）
- **Tracing** — 记录 Loader / Chunker / Embedder 各阶段耗时（可选）

## 六、关键设计权衡

1. **双库架构（MySQL + PostgreSQL）** — MySQL 存储结构化元数据和全文，便于关系查询和业务逻辑；PostgreSQL + pgvector 专门存储向量，HNSW 索引检索性能优（ANN 查询 < 50ms）。代价是数据同步需要事务协调。

2. **异步摄入 + Job 状态跟踪** — 大文件解析耗时长（PDF 10 页约 3-5s），同步阻塞 HTTP 请求不可接受。异步任务允许前端轮询进度，用户体验更好。

3. **多模态抽取使用 VLM 而非 OCR** — OCR 只能提取文字，无法理解图表语义（如柱状图数值对比）。VLM caption 生成自然语言描述，检索召回更准确。代价是每页 1 次 LLM 调用（约 $0.001 / 页）。

4. **RecursiveChunker 默认 500 字 + 80 字重叠** — 平衡检索粒度和上下文完整性。chunk 过小（< 200）缺乏语义，过大（> 1000）检索噪音高。overlap 保证跨 chunk 边界的句子不被截断。

5. **幂等由 (kb_id, content_hash) 唯一约束保证** — 防止用户重复上传同一文件浪费向量配额。代价是文件修改后 hash 变化会被当作新文档（符合预期）。

6. **HNSW 索引参数 m=16, ef_construction=64** — pgvector 默认 m=16（邻接边数），ef_construction=64（构建时搜索宽度）。平衡索引构建速度（摄入时）和检索召回率。生产环境可根据数据规模调整（如 10M+ chunk 用 m=32）。

## 七、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **多租户隔离** | 所有 MySQL 查询 | MyBatis-Plus WorkspaceFilterInterceptor 自动注入 WHERE workspace_id = ? |
| **异步执行** | IngestPipeline | @Async 线程池（Spring TaskExecutor） |
| **事务一致性** | Persist 阶段 | @Transactional 保证 MySQL + PG 双写原子性（需配置 JTA 或手动回滚） |
| **重试** | ModelGateway 调用 | Resilience4j Retry（embed / vision 失败重试 3 次） |
| **追踪** | 各阶段 | TracingService.addStep(type=INGEST_LOAD / INGEST_CHUNK / INGEST_EMBED) |
| **限流** | 上传接口 | Spring MVC 文件大小限制（max-file-size: 50MB） |

## 八、未来扩展点

1. **增量更新** — 文档修改后仅重新摄入变更的 chunk，而非全文重建。需要 diff 算法和 chunk 版本管理。

2. **批量摄入** — 支持 ZIP / 文件夹上传，并行处理多个文档。需要任务队列和 Worker 池。

3. **自定义 Chunker** — 允许用户配置分块策略（按段落 / 按句子 / 按固定 token 数）。需要 Chunker 插件机制。

4. **Table 专用解析** — 使用 Camelot / Tabula 库提取 PDF 表格结构，转为结构化 JSON 而非纯文本。检索时可做字段级过滤。

5. **跨模态检索** — 支持图片 embedding（CLIP），用户上传图片查询相似 chunk。需要 multi-modal embedding 模型。

6. **分布式向量索引** — pgvector 单机 10M chunk 性能瓶颈，迁移到 Milvus / Qdrant 支持分片和副本。

7. **摄入质量评分** — 评估 chunk 语义完整性、去重率、向量分布均匀度，自动标记低质量文档提示用户优化。
