# PromptHub 功能分析与生产调用链

> 对应 spec：`docs/specs/b2-prompt-hub.md`
> 写于 2026-06-08

## 一、定位与职责

PromptHub 是 Platform Core 的提示词管理子系统，为 Agent 和 Workflow 提供集中化的 Prompt 模板管理。它支持版本控制、Pebble 模板渲染、URI 引用，解决 Prompt 硬编码、变量替换不安全、多团队协作困难等问题。

## 二、核心能力

1. **版本化存储** — 按 `name@version` 管理 Prompt，支持 `latest` 语义
2. **Pebble 模板渲染** — 安全的变量替换、条件分支、循环，编译缓存
3. **URI 引用** — `prompt://doc_qa/system@v3` 格式，Agent/Workflow 无需关心存储位置
4. **Schema 校验** — 变量 schema（JSON Schema 简化版）定义必填字段和类型
5. **多租户隔离** — 每个 workspace 独立的 Prompt 命名空间
6. **CRUD REST API** — 在线编辑、预览、发布

## 三、对外接口与数据契约

### 核心接口

```java
package io.kyligence.ragagent.core.prompt;

public interface PromptService {
    Prompt save(Long workspaceId, SavePromptCommand cmd);
    String render(Long workspaceId, String name, String version, 
                  Map<String, Object> variables);
    String renderUri(Long workspaceId, String uri, 
                     Map<String, Object> variables);
    Prompt get(Long workspaceId, String name, String version);
    List<Prompt> list(Long workspaceId);
    void delete(Long workspaceId, String name, String version);
}
```

### 数据表

**prompt** 表：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | Prompt UUID |
| workspace_id | BIGINT | 租户隔离 |
| name | VARCHAR(128) | 名称（如 doc_qa/system） |
| version | VARCHAR(32) | 版本（如 v1, v2, latest） |
| template | LONGTEXT | Pebble 模板内容 |
| variables_schema | TEXT | 变量 schema JSON（可选） |
| description | VARCHAR(512) | 描述 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

**唯一索引**：`(workspace_id, name, version)`

### URI 解析规则

```
prompt://name@version  → name="name", version="version"
prompt://name          → name="name", version="latest"
name@version           → name="name", version="version" (scheme可省略)
```

## 四、生产环境真实调用链

### 场景：Agent 渲染 system prompt 用于 LLM 调用

```
┌──────────────────────────────────────────────────────────┐
│ Agent 准备调用 LLM，需要 system prompt                    │
│ Agent 配置中指定: systemPromptUri = "prompt://doc_qa/    │
│                                      system@v3"           │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ① AgentExecutor 调用 PromptService                        │
│    promptService.renderUri(                               │
│      workspaceId = 7,                                     │
│      uri = "prompt://doc_qa/system@v3",                   │
│      variables = {                                        │
│        "user_name": "Alice",                              │
│        "current_date": "2026-06-08",                      │
│        "kb_context": "财务知识库"                          │
│      }                                                    │
│    )                                                      │
│                                                           │
│    ├─ PromptUri.parse("prompt://doc_qa/system@v3")       │
│    │    返回: PromptUri{name="doc_qa/system", ver="v3"}  │
│    │                                                      │
│    └─ promptService.render(7, "doc_qa/system", "v3", vars)│
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ② PromptServiceImpl.render()                             │
│    ├─ 解析 version（非 "latest" 直接用）                  │
│    │                                                      │
│    ├─ 查询 DB:                                            │
│    │   SELECT * FROM prompt                               │
│    │   WHERE workspace_id = 7                             │
│    │     AND name = 'doc_qa/system'                       │
│    │     AND version = 'v3';                              │
│    │                                                      │
│    │   返回: Prompt {                                     │
│    │     id = "prompt-uuid-123",                          │
│    │     template = "You are {{role}}. Today is           │
│    │                 {{current_date}}. Knowledge base:    │
│    │                 {{kb_context}}. {% if formal %}      │
│    │                 Use formal tone.{% endif %}"         │
│    │   }                                                  │
│    │                                                      │
│    └─ 调用 PromptRenderer.render(template, variables)    │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ③ PromptRenderer.render()                                │
│    ├─ 计算 cacheKey = hash(template) = "a3f2b1c0"        │
│    │                                                      │
│    ├─ 检查编译缓存:                                       │
│    │   cache.get("a3f2b1c0") → PebbleTemplate已存在       │
│    │   (缓存命中，跳过编译)                                │
│    │                                                      │
│    ├─ 执行渲染:                                           │
│    │   tpl.evaluate(writer, variables)                    │
│    │   - 替换 {{role}} → "helpful assistant"              │
│    │   - 替换 {{current_date}} → "2026-06-08"            │
│    │   - 替换 {{kb_context}} → "财务知识库"               │
│    │   - 条件 {% if formal %} 未匹配（formal=null）       │
│    │                                                      │
│    └─ 返回渲染结果:                                       │
│        "You are helpful assistant. Today is 2026-06-08.   │
│         Knowledge base: 财务知识库."                      │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ④ 返回给 Agent                                            │
│    renderedPrompt = "You are helpful assistant. ..."      │
│                                                           │
│    Agent 将其作为 messages[0] 发送给 ModelGateway:        │
│    modelGateway.chat(ChatRequest{                         │
│      messages: [                                          │
│        {role: "system", content: renderedPrompt},         │
│        {role: "user", content: "Q1财报数据是多少？"}       │
│      ]                                                    │
│    })                                                     │
└──────────────────────────────────────────────────────────┘

性能指标：
- DB 查询: ~5ms (索引命中)
- Pebble 渲染: ~2ms (缓存命中，无需编译)
- 总耗时: ~7ms
```

### 场景 2：latest 版本解析

```
┌──────────────────────────────────────────────────────────┐
│ 调用 renderUri("prompt://doc_qa/system")  (无 @version)   │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ PromptUri.parse() → version = "latest"                   │
│                                                           │
│ PromptServiceImpl.render() 处理 latest:                  │
│   SELECT * FROM prompt                                    │
│   WHERE workspace_id = 7                                  │
│     AND name = 'doc_qa/system'                            │
│   ORDER BY updated_at DESC                                │
│   LIMIT 1;                                                │
│                                                           │
│ → 返回最新更新的版本（可能是 v5）                          │
└──────────────────────────────────────────────────────────┘
```

## 五、与其他子系统的协作

```
┌───────────────────────────────────────────────┐
│     Agent / Workflow 业务层                    │
│  - AgentExecutor                               │
│  - WorkflowNodeExecutor                        │
└───────────┬───────────────────────────────────┘
            │ @Autowired
            ▼
┌───────────────────────────────────────────────┐
│     PromptService                              │
│  - renderUri()                                 │
│  - render()                                    │
└───────────┬───────────────────────────────────┘
            │
            ├─ PromptRenderer (Pebble)
            │    └─ 编译缓存 (ConcurrentHashMap)
            │
            └─ MyBatis-Plus
                  ▼
┌───────────────────────────────────────────────┐
│     MySQL (prompt 表)                          │
│  - 唯一索引: (workspace_id, name, version)     │
│  - 查询索引: workspace_id                      │
└───────────────────────────────────────────────┘

关键集成点：
1. Agent 初始化时解析 systemPromptUri
2. Workflow 节点（如 LlmNode）调用 renderUri 渲染输入模板
3. REST API 管理界面通过 PromptController CRUD
```

## 六、关键设计权衡

1. **Pebble 而非 FreeMarker / Velocity** — Pebble 是沙箱安全的轻量模板引擎，禁止反射调用 Java 方法，防止模板注入攻击。FreeMarker 可通过 `?new` 实例化任意类。

2. **编译缓存用 template hashCode 而非 DB ID** — 避免重复编译相同模板。hashCode 冲突概率低，且 cache miss 只是性能降级，不影响正确性。

3. **latest 按 updated_at 排序而非 version 字段** — version 是字符串（v1, v2, beta），无法自然排序。updated_at 准确反映"最新修改"语义。

4. **autoEscaping=false** — Prompt 用于 LLM 输入，不需要 HTML 转义。保持原始文本格式。

5. **DB 存储而非文件系统** — 支持在线编辑、版本管理、权限控制。文件系统需要 GitOps 流程，一期复杂度高。

## 七、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **多租户隔离** | 所有 Prompt 查询 | `PromptMapper` 标注 `@WorkspaceAware`，自动注入 `WHERE workspace_id = ?` |
| **缓存** | Pebble 模板编译 | `PromptRenderer` 内部 `ConcurrentHashMap<hash, PebbleTemplate>` |
| **异常处理** | 模板语法错误 | 编译时抛 `PlatformException(PROMPT_RENDER_FAILED)`，调用方捕获后降级到默认 prompt 或失败 |
| **幂等性** | save() Upsert | 同一 `(workspace_id, name, version)` 重复 save 执行 UPDATE 而非 INSERT，幂等 |

## 八、未来扩展点

1. **变量 schema 校验** — 一期 `variables_schema` 字段存储但不校验。二期可集成 JSON Schema Validator，在 render 前校验必填字段和类型。

2. **Prompt 版本 diff** — 可视化对比 v2 和 v3 的模板差异，类似 Git diff。

3. **A/B 测试** — 同一 name 配置多个 version，按百分比流量分配（如 80% 用 v2，20% 用 v3），收集效果数据后决定全量发布。

4. **Prompt 继承** — 支持 `extends` 语法，子模板继承父模板的 block，减少重复。

5. **多语言支持** — `prompt://doc_qa/system@v3:zh-CN` 格式，按语言后缀加载不同模板。

6. **实时预览 API** — `/prompts/{name}/{version}/preview` 端点，前端编辑器实时渲染效果，类似 Markdown 预览。
