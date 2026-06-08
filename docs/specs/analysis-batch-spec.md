# Analysis Batch Spec：为 docs/specs 下的设计写 analysis

> 元任务：把 `docs/specs/` 里现有 spec（B1/B2/B4/B4-security-fixes/B5/B6/B7/C1/C2）翻译成
> `docs/analysis/<name>-analysis.md`，每篇侧重"功能描述 + 生产环境真实调用链"。
> 不是简单复述 spec，而是**站在使用方视角**，把功能、对外边界、跨模块协作、生产链路讲清。

## 输入

- `docs/specs/2026-05-16-rag-agent-workflow-platform-design.md` —— 总架构（背景/术语对齐用）
- 各份 B/C spec（详见任务清单）
- 已有参考样例：
  - `docs/analysis/2026-05-23-project-overview-and-call-chain.md`（项目级总览 + Agent ReAct 完整链路）
  - `docs/analysis/b3-memory-store-analysis.md`（单子系统 analysis 的样板）

## 输出位置 + 命名

| Spec | 输出文件 |
|------|----------|
| `b1-phase0-dependencies.md` | `docs/analysis/b1-tracing-analysis.md` |
| `b2-prompt-hub.md` | `docs/analysis/b2-prompt-hub-analysis.md` |
| `b4-model-gateway.md` + `b4-security-fixes.md` | `docs/analysis/b4-model-gateway-analysis.md`（一篇覆盖，security-fixes 单独成节） |
| `b5-tool-registry.md` | `docs/analysis/b5-tool-registry-analysis.md` |
| `b6-sandbox-runner.md` | `docs/analysis/b6-sandbox-runner-analysis.md` |
| `b7-event-bus.md` | `docs/analysis/b7-event-bus-analysis.md` |
| `c1-ingest-pipeline.md` | `docs/analysis/c1-ingest-pipeline-analysis.md` |
| `c2-retriever.md` | `docs/analysis/c2-retriever-analysis.md` |

> B1 spec 包含 Phase 0（pom 依赖）+ Phase 1（Tracing 子系统）；analysis 只覆盖 Tracing 这个能力，依赖只在"前置条件"一节一句话带过即可。

## 每篇 analysis 的标准结构

```markdown
# <子系统中文名> 功能分析与生产调用链

> 对应 spec：`docs/specs/<spec-file>.md`
> 写于 2026-06-08

## 一、定位与职责
（2-4 句话讲清楚：是什么、为谁服务、解决什么问题、在整个平台里扮演什么角色）

## 二、核心能力
（按 spec 把对外能力列成 3-6 条，每条带一行解释。例如 ModelGateway：chat / chatStream / embed / rerank / provider 管理 / 调用追踪）

## 三、对外接口与数据契约
（关键 Java 接口签名 + 入参出参摘要 + 关键 DTO 字段。SQL 表用 markdown 表格列字段。无需 1:1 抄 spec，只列对**调用方**有意义的部分）

## 四、生产环境真实调用链
（重头戏。挑 1-2 个**典型**生产场景，画出 ascii 时序/链路图，逐步骤标注：
  - 谁调用谁
  - 经过哪些 Spring 组件 / 数据库 / 外部 API
  - 关键 SQL / HTTP 请求长什么样
  - 失败/重试/兜底走哪个分支
风格参考 `docs/analysis/2026-05-23-project-overview-and-call-chain.md` 第四节。）

## 五、与其他子系统的协作
（依赖了谁、被谁依赖。用 ASCII 图或表格表达，至少标 2-3 个关键集成点）

## 六、关键设计权衡
（spec 里隐含的设计选择：为什么这么做、不那么做的代价。3-5 条）

## 七、横切关注点（按需）
（多租户、加密、限流、重试、追踪、流式等，凡是该子系统**实际触发**的横切都列出）

## 八、未来扩展点
（spec 里明确提到的"留接口"或"一期不实现"的部分，提示后续阶段会接入哪里）
```

## 写作要求

1. **不复述 spec**：spec 是"做什么"，analysis 是"怎么用、生产里怎么跑"。如果一段内容只是把 spec 段落原封不动搬过来，直接砍掉。
2. **生产调用链是核心**：每篇至少 1 个完整 ASCII 调用链。链路要落到具体的类/表/HTTP 端点/SQL 片段，不能停留在"调用 service 层"这种抽象。
3. **多租户隔离 + Tracing + Resilience4j retry** 这三个横切关注点，如果该子系统真正触发，在第七节明确标注触发点。
4. **B4 ModelGateway** 的 analysis 必须包含一节 `## 七、安全增强（b4-security-fixes）`，覆盖 AES/GCM 替换 ECB、IV 处理、key 强度校验、tracing 不打印 key 等 fix 项，并给出修复后的 encrypt/decrypt 调用链。
5. **C1 Ingest Pipeline** 篇幅会偏大（spec 1500 行），调用链至少覆盖一个 PDF + 图片的多模态摄入完整流程：上传 → Loader → Cleaner → 多模态抽取（vision caption）→ Chunker → Embedder → pgvector 落库 + 一个 IngestJob 状态机迁移。
6. **C2 Retriever** 调用链覆盖 `query → embed → pgvector HNSW → bge-reranker → topK 返回`，并展示 RerankingRetriever 装饰器是怎么包裹 VectorRetriever 的。
7. **B5 ToolRegistry** 调用链至少给一个 JAVA_BEAN 工具和一个 HTTP 工具的对比示例。
8. **B7 EventBus** 调用链覆盖：发布端持久化 `platform_event` → `@TransactionalEventListener` 异步分发 → 消费方幂等校验，并标注一期 5 种事件类型。
9. **代码片段使用 Java**，SQL 用 markdown 代码块标注 `sql`。链路图用 ASCII 框图，宽度不超过 76 字符，避免折行。
10. **日期统一写 2026-06-08**。

## 验收标准

- [ ] 8 份 analysis 文件全部生成在 `docs/analysis/` 下
- [ ] 每篇都包含上述 8 节（B4 多 1 节安全增强）
- [ ] 每篇都有至少一个完整的 ASCII 生产调用链
- [ ] 文件名严格按照"输出位置 + 命名"表
- [ ] 不修改 `docs/specs/` 下任何文件
- [ ] 不修改任何代码
