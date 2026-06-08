# ToolRegistry 功能分析与生产调用链

> 对应 spec：`docs/specs/b5-tool-registry.md`
> 写于 2026-06-08

## 一、定位与职责

ToolRegistry 是 Platform Core 的工具注册中心，为 Agent 和 Workflow 提供统一的工具发现和调用能力。它支持四种实现类型（JAVA_BEAN / HTTP / WORKFLOW_REF / RETRIEVER_REF），屏蔽底层差异，让 Agent 通过工具名称即可调用任意工具，实现 LLM function-calling 和外部能力扩展。

## 二、核心能力

1. **工具注册** — JAVA_BEAN 自动扫描注册，HTTP 从 DB 动态加载
2. **统一调用接口** — `invoke(name, input, ctx)` 屏蔽实现差异
3. **Schema 描述** — 每个工具提供 JSON Schema（供 LLM function-calling）
4. **多实现类型** — JAVA_BEAN（本地 Spring Bean）、HTTP（外部 API）、WORKFLOW_REF / RETRIEVER_REF（占位）
5. **工具发现** — `list()` 返回所有可用工具的 name + description + schema
6. **持久化配置** — HTTP 工具配置存 DB，支持 CRUD

## 三、对外接口与数据契约

### 核心接口

```java
package io.kyligence.ragagent.core.tool;

public interface ToolRegistry {
    void register(Tool tool);
    Optional<Tool> find(String name);
    ToolResult invoke(String name, ToolInput input, ToolContext ctx);
    List<ToolSummary> list();
    
    record ToolSummary(String name, String description, 
                       String inputSchemaJson) {}
}

public interface Tool {
    String name();
    String description();
    String inputSchemaJson(); // JSON Schema for LLM
    ToolResult invoke(ToolInput input, ToolContext ctx);
}
```

### 数据表

**tool_def** 表：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | Tool UUID |
| workspace_id | BIGINT | 租户隔离 |
| name | VARCHAR(128) | 工具名称（如 search_api） |
| description | VARCHAR(512) | 工具描述 |
| impl_type | VARCHAR(32) | JAVA_BEAN / HTTP / WORKFLOW_REF / RETRIEVER_REF |
| input_schema_json | TEXT | 输入参数 JSON Schema |
| output_schema_json | TEXT | 输出结果 JSON Schema（可选） |
| config_json | TEXT | 配置（HTTP type: url/method/headers/timeout） |
| enabled | BOOLEAN | 是否启用 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

**唯一索引**：`(workspace_id, name)`

### Tool 调用流程

```java
// 1. Agent 从 LLM 获取 tool_call
ToolCall tc = llmResponse.getToolCalls().get(0);
String toolName = tc.getName();  // "search_api"
String argsJson = tc.getArguments(); // '{"query":"RAG"}'

// 2. 解析参数
Map<String, Object> params = objectMapper.readValue(argsJson, Map.class);
ToolInput input = new ToolInput(params);

// 3. 构建上下文
ToolContext ctx = new ToolContext(runId, workspaceId, userId);

// 4. 调用工具
ToolResult result = toolRegistry.invoke(toolName, input, ctx);

// 5. 处理结果
if (result.success()) {
    String output = result.output().value().toString();
    // 将 output 作为 tool_result 返回给 LLM
} else {
    // 重试或降级
}
```

## 四、生产环境真实调用链

### 场景 1：JAVA_BEAN 工具 — 计算器

```
┌──────────────────────────────────────────────────────────┐
│ ① 系统启动，Spring 容器扫描                                │
│    @Component                                             │
│    public class CalculatorTool extends JavaBeanTool {     │
│      @Override                                            │
│      public String name() { return "calculator"; }        │
│                                                           │
│      @Override                                            │
│      public ToolResult invoke(ToolInput in, ToolContext c)│
│      {                                                    │
│        String expr = in.getString("expression");          │
│        double result = eval(expr);                        │
│        return ToolResult.ok(result);                      │
│      }                                                    │
│    }                                                      │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ② ToolRegistryImpl.autoRegister() (@PostConstruct)       │
│    ApplicationContext.getBeansOfType(JavaBeanTool.class)  │
│      → 发现 CalculatorTool Bean                           │
│                                                           │
│    tools.put("calculator", calculatorToolBean);           │
│    log.info("Registered JavaBeanTool: calculator");       │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ③ Agent 运行时，LLM 返回 tool_call                        │
│    ChatResponse{                                          │
│      toolCalls = [ToolCall{                               │
│        name = "calculator",                               │
│        arguments = '{"expression": "150 * 1.15"}'         │
│      }]                                                   │
│    }                                                      │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ④ Agent 调用 ToolRegistry                                 │
│    toolRegistry.invoke(                                   │
│      name = "calculator",                                 │
│      input = ToolInput{params={expression:"150 * 1.15"}},│
│      ctx = ToolContext{runId="run-abc", wsId="7", ...}    │
│    )                                                      │
│                                                           │
│    ├─ tools.get("calculator") → CalculatorTool Bean      │
│    └─ calculatorTool.invoke(input, ctx)                  │
│         → eval("150 * 1.15") = 172.5                      │
│         → return ToolResult.ok(172.5)                     │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ⑤ 返回给 Agent                                            │
│    ToolResult{                                            │
│      success = true,                                      │
│      output = ToolOutput{value=172.5, metadata={}},       │
│      errorMessage = null                                  │
│    }                                                      │
│                                                           │
│    Agent 将结果包装为 LLM 的下一轮输入:                     │
│    messages.add(ChatMessage.tool(                         │
│      "172.5",                                             │
│      "calculator"                                         │
│    ))                                                     │
└──────────────────────────────────────────────────────────┘

性能：
- 注册时机: 启动时一次性 (耗时 <10ms)
- 调用延迟: ~1ms (本地 Java 方法调用)
- 无网络 IO，无 DB 查询
```

### 场景 2：HTTP 工具 — 外部搜索 API

```
┌──────────────────────────────────────────────────────────┐
│ ① 管理员通过 REST API 创建 HTTP 工具                       │
│    POST /api/v1/workspaces/7/tools                        │
│    Body: {                                                │
│      "name": "search_api",                                │
│      "description": "Search external knowledge",          │
│      "implType": "HTTP",                                  │
│      "inputSchemaJson": '{                                │
│        "type":"object",                                   │
│        "properties":{"query":{"type":"string"}},          │
│        "required":["query"]                               │
│      }',                                                  │
│      "configJson": '{                                     │
│        "url": "https://api.example.com/search",           │
│        "method": "POST",                                  │
│        "headers": {"X-API-Key": "sk-xxx"},                │
│        "timeout_seconds": 30                              │
│      }'                                                   │
│    }                                                      │
│                                                           │
│    SQL:                                                   │
│    INSERT INTO tool_def (                                 │
│      id, workspace_id, name, impl_type, config_json, ...  │
│    ) VALUES (                                             │
│      UUID(), 7, 'search_api', 'HTTP', '{...}', ...        │
│    );                                                     │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ② Agent 启动时懒加载 workspace 工具                        │
│    toolRegistry.loadWorkspaceTools(workspaceId = 7)       │
│                                                           │
│    SQL:                                                   │
│    SELECT * FROM tool_def                                 │
│    WHERE workspace_id = 7                                 │
│      AND enabled = true                                   │
│      AND impl_type = 'HTTP';                              │
│                                                           │
│    返回: [ToolDef{name="search_api", configJson="..."}]   │
│                                                           │
│    对每个 ToolDef:                                         │
│      HttpTool httpTool = new HttpTool(def, restTemplate,  │
│                                       objectMapper);       │
│      tools.put("search_api", httpTool);                   │
│      log.info("Loaded HTTP tool: search_api");            │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ③ Agent ReAct 循环，LLM 返回 tool_call                    │
│    ChatResponse{                                          │
│      toolCalls = [ToolCall{                               │
│        name = "search_api",                               │
│        arguments = '{"query":"Retrieval-Augmented         │
│                      Generation"}'                        │
│      }]                                                   │
│    }                                                      │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ④ Agent 调用 ToolRegistry                                 │
│    toolRegistry.invoke(                                   │
│      name = "search_api",                                 │
│      input = ToolInput{params={query:"RAG"}},             │
│      ctx = ToolContext{...}                               │
│    )                                                      │
│                                                           │
│    ├─ tools.get("search_api") → HttpTool 实例             │
│    └─ httpTool.invoke(input, ctx)                        │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ⑤ HttpTool.invoke() 执行                                  │
│    ├─ 解析 configJson:                                    │
│    │    url = "https://api.example.com/search"            │
│    │    method = "POST"                                   │
│    │    headers = {"X-API-Key": "sk-xxx"}                 │
│    │                                                      │
│    ├─ 构建 HTTP 请求:                                     │
│    │    HttpHeaders headers = new HttpHeaders();          │
│    │    headers.setContentType(APPLICATION_JSON);         │
│    │    headers.set("X-API-Key", "sk-xxx");               │
│    │                                                      │
│    │    String body = objectMapper.writeValueAsString(    │
│    │      input.params()                                  │
│    │    ); // '{"query":"RAG"}'                           │
│    │                                                      │
│    │    HttpEntity<String> entity = new HttpEntity<>(body,│
│    │                                                headers);│
│    │                                                      │
│    ├─ 发送 HTTP 请求:                                     │
│    │    POST https://api.example.com/search               │
│    │    X-API-Key: sk-xxx                                 │
│    │    Content-Type: application/json                    │
│    │    Body: {"query":"RAG"}                             │
│    │                                                      │
│    │    (耗时 280ms)                                       │
│    │                                                      │
│    └─ 解析响应:                                           │
│        ResponseEntity<String> resp = restTemplate         │
│          .exchange(url, POST, entity, String.class);      │
│                                                           │
│        String responseBody = resp.getBody();              │
│        // '{"results":[{"title":"RAG论文","url":"..."}]}'  │
│                                                           │
│        return ToolResult.ok(responseBody);                │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ⑥ 返回给 Agent                                            │
│    ToolResult{                                            │
│      success = true,                                      │
│      output = ToolOutput{                                 │
│        value = '{"results":[...]}',                       │
│        metadata = {}                                      │
│      }                                                    │
│    }                                                      │
│                                                           │
│    Agent 将结果传给 LLM 继续推理                            │
└──────────────────────────────────────────────────────────┘

性能：
- 加载工具: 启动时一次性 SQL 查询 (~10ms)
- HTTP 调用: ~280ms (外部 API 延迟)
- 总耗时: ~285ms
```

### JAVA_BEAN vs HTTP 对比

```
┌─────────────────┬──────────────────┬──────────────────┐
│ 维度             │ JAVA_BEAN        │ HTTP             │
├─────────────────┼──────────────────┼──────────────────┤
│ 注册方式         │ @Component 自动   │ DB 配置 + 懒加载  │
│ 存储位置         │ 代码编译期        │ tool_def 表      │
│ 调用延迟         │ ~1ms (本地)      │ ~100-500ms (网络)│
│ 部署要求         │ 需要重启应用      │ 在线增删改        │
│ 典型场景         │ 内置工具(计算器)  │ 外部 API(搜索)   │
│ 安全隔离         │ 同进程           │ 网络隔离         │
└─────────────────┴──────────────────┴──────────────────┘
```

## 五、与其他子系统的协作

```
┌───────────────────────────────────────────────┐
│       Agent / Workflow 业务层                  │
│  - AgentExecutor (ReAct Loop)                  │
│  - WorkflowToolNode                            │
└───────────┬───────────────────────────────────┘
            │ @Autowired
            ▼
┌───────────────────────────────────────────────┐
│       ToolRegistry                             │
│  - register() / find() / invoke()              │
│  - autoRegister() (@PostConstruct)             │
│  - loadWorkspaceTools() (懒加载)               │
└───────────┬───────────────────────────────────┘
            │
            ├─ JavaBeanTool (Spring Bean)
            │    └─ ApplicationContext 扫描注册
            │
            ├─ HttpTool (RestTemplate)
            │    └─ 动态构造，config 从 DB 读取
            │
            └─ MyBatis-Plus
                  ▼
┌───────────────────────────────────────────────┐
│       MySQL (tool_def 表)                      │
│  - 唯一索引: (workspace_id, name)              │
└───────────────────────────────────────────────┘

关键集成点：
1. Agent LLM function-calling → ToolRegistry.invoke()
2. Workflow ToolNode → ToolRegistry.find() + invoke()
3. LLM 需要工具列表 → ToolRegistry.list() 转 OpenAI tools 格式
```

## 六、关键设计权衡

1. **JAVA_BEAN 不存 DB** — Spring Bean 生命周期即注册，避免 DB 和代码双重维护。HTTP / WORKFLOW_REF / RETRIEVER_REF 等外部工具存 DB，支持在线配置。

2. **注册表用 ConcurrentHashMap** — 单进程内线程安全，读写性能高。无分布式需求（工具调用本地执行或代理转发）。

3. **HTTP 工具用 RestTemplate 而非 WebClient** — 同步调用符合工具语义（Agent 等待工具返回后继续推理）。WebClient 异步在此场景无优势，且增加复杂度。

4. **懒加载 workspace 工具** — 避免启动时全量加载所有 workspace 的 HTTP 工具（可能上千个）。Agent 首次启动时调用 `loadWorkspaceTools(wsId)`。

5. **inputSchemaJson 用 JSON Schema** — OpenAI function-calling 标准格式，LLM 原生理解。一期不做校验，二期可集成 `json-schema-validator` 在 invoke 前校验。

6. **WORKFLOW_REF / RETRIEVER_REF 一期占位** — 依赖 Plan C（RAG）和 Plan D（Workflow）未实现，暂返回 `UnsupportedOperationException`。接口已定义，后续无需改 ToolRegistry。

## 七、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **多租户隔离** | HTTP 工具加载 | `ToolDefMapper` 标注 `@WorkspaceAware`，自动注入 `WHERE workspace_id = ?` |
| **异常处理** | 工具调用失败 | HttpTool 捕获异常，返回 `ToolResult.failure(errorMsg)`，不抛异常给 Agent |
| **超时控制** | HTTP 工具 | RestTemplate 配置 timeout（从 configJson 读取，默认 30s） |
| **追踪** | 工具调用 | Agent 层调用 `TracingService.addStep(type=TOOL)`，记录输入输出和延迟 |
| **幂等性** | 重复调用 | JAVA_BEAN 工具需自行保证；HTTP 工具无幂等保证（依赖外部 API） |

## 八、未来扩展点

1. **WORKFLOW_REF 实现** — 包装 Workflow 为工具，Agent 可调用完整 Workflow 子图。

   ```java
   public class WorkflowRefTool implements Tool {
       private final WorkflowExecutor executor;
       
       @Override
       public ToolResult invoke(ToolInput input, ToolContext ctx) {
           String workflowId = config.get("workflow_id");
           WorkflowResult result = executor.execute(workflowId, input);
           return ToolResult.ok(result.getOutput());
       }
   }
   ```

2. **RETRIEVER_REF 实现** — 包装 Retriever 为工具，Agent 可直接调用知识库检索。

   ```java
   public class RetrieverRefTool implements Tool {
       private final Retriever retriever;
       
       @Override
       public ToolResult invoke(ToolInput input, ToolContext ctx) {
           String query = input.getString("query");
           List<Chunk> chunks = retriever.retrieve(query, 5);
           return ToolResult.ok(chunks);
       }
   }
   ```

3. **工具权限控制** — 按 userId / role 限制工具调用（如敏感工具只有 admin 可用）。

4. **工具调用限流** — 防止恶意 Agent 频繁调用外部 API 耗尽额度。

5. **工具结果缓存** — 相同输入的工具调用复用缓存结果（如天气查询、汇率查询）。

6. **工具编排 (Tool Chaining)** — 一个工具的输出自动作为下一个工具的输入，减少 LLM 中间推理步骤。

7. **工具测试沙箱** — 创建工具时先在沙箱环境试调用，验证 config 正确性和返回格式。

8. **异步工具** — 支持长时间运行的工具（如视频生成），返回 jobId，Agent 轮询状态。
