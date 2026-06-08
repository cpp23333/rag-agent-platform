# ModelGateway 功能分析与生产调用链

> 对应 spec：`docs/specs/b4-model-gateway.md` + `docs/specs/b4-security-fixes.md`
> 写于 2026-06-08

## 一、定位与职责

ModelGateway 是 Platform Core 的统一模型调用入口，为 Agent / Workflow / RAG 屏蔽不同 LLM provider 的差异。它封装 Provider 路由、API Key 加密存储、Resilience4j 重试、超时控制、调用追踪、成本计费，提供一致的 chat / embed / rerank 接口。

## 二、核心能力

1. **统一接口** — `chat` / `chatStream` / `embed` / `rerank` 四种调用模式
2. **多 Provider 支持** — OpenAI / DeepSeek / Claude / 火山等，统一走 OpenAI-compatible 协议
3. **Provider 管理** — 持久化配置（baseUrl / apiKey / models），CRUD REST API
4. **API Key 加密** — AES/GCM 对称加密落库，密钥从环境变量读取
5. **重试与超时** — Resilience4j retry（3次指数退避）+ 120s 超时
6. **流式输出** — Reactor Flux SSE，支持 Agent 流式回复
7. **自动追踪** — 每次调用记录到 TracingService（tokens / cost / latency）

## 三、对外接口与数据契约

### 核心接口

```java
package io.kyligence.ragagent.core.model;

public interface ModelGateway {
    ChatResponse chat(ChatRequest request);
    Flux<String> chatStream(ChatRequest request);
    EmbeddingResponse embed(EmbeddingRequest request);
    RerankResponse rerank(RerankRequest request);
}

// Request DTOs
record ChatRequest(String model, List<ChatMessage> messages, 
                   Double temperature, Integer maxTokens, 
                   List<Map<String, Object>> tools, 
                   Map<String, Object> extraParams) {}

record EmbeddingRequest(String model, List<String> inputs) {}

record RerankRequest(String model, String query, 
                     List<String> documents, int topN) {}
```

### 数据表

**model_provider** 表：

| 字段 | 类型 | 说明 |
|------|------|------|
| id | VARCHAR(36) | Provider UUID |
| workspace_id | BIGINT | 租户隔离 |
| name | VARCHAR(64) | Provider 名称（如 openai） |
| type | VARCHAR(32) | openai / openai-compat / claude / azure-openai |
| base_url | VARCHAR(512) | API 端点（如 https://api.openai.com/v1） |
| api_key_encrypted | VARCHAR(1024) | AES/GCM 加密后的 API Key |
| models_json | TEXT | 支持的模型列表（JSON） |
| enabled | BOOLEAN | 是否启用 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

**唯一索引**：`(workspace_id, name)`

### Model Reference 格式

```
"openai/gpt-4o-mini"  → provider=openai, model=gpt-4o-mini
"deepseek/deepseek-chat" → provider=deepseek, model=deepseek-chat
null → 使用配置的 defaultChat
```

## 四、生产环境真实调用链

### 场景：Agent 调用 LLM 并自动重试

```
┌──────────────────────────────────────────────────────────┐
│ Agent ReAct Loop 需要 LLM 推理                            │
│ AgentExecutor.think()                                     │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ① 调用 ModelGateway                                       │
│    modelGateway.chat(ChatRequest{                         │
│      model = "openai/gpt-4o-mini",                        │
│      messages = [                                         │
│        {role: "system", content: systemPrompt},           │
│        {role: "user", content: "Q1财报数据是多少？"}       │
│      ],                                                   │
│      temperature = 0.7,                                   │
│      maxTokens = 2000,                                    │
│      tools = [{type: "function", function: {...}}]        │
│    })                                                     │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ② ModelGatewayImpl.chat()                                │
│    ├─ 解析 model reference: "openai/gpt-4o-mini"         │
│    │    providerName = "openai"                           │
│    │    modelName = "gpt-4o-mini"                         │
│    │                                                      │
│    ├─ resolve(providerName):                             │
│    │    ModelProviderService.getByName("openai")          │
│    │                                                      │
│    │    SQL:                                              │
│    │    SELECT * FROM model_provider                      │
│    │    WHERE workspace_id = 7                            │
│    │      AND name = 'openai'                             │
│    │      AND enabled = true;                             │
│    │                                                      │
│    │    返回: ModelProvider {                             │
│    │      baseUrl = "https://api.openai.com/v1",          │
│    │      apiKeyEncrypted = "Ax3f...B9==" (base64)        │
│    │    }                                                 │
│    │                                                      │
│    └─ decryptApiKey(apiKeyEncrypted)                     │
│         → "sk-proj-abc...xyz123"                          │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ③ Resilience4j Retry 包装调用                             │
│    Retry.decorateSupplier(retry, () -> {                 │
│      return client.chat(baseUrl, apiKey, request);        │
│    }).get()                                               │
│                                                           │
│    重试配置:                                              │
│    - maxAttempts = 3                                      │
│    - waitDuration = 500ms (指数退避)                      │
│    - retryExceptions = [PlatformException.class]          │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ④ OpenAiCompatClient.chat()                              │
│    ├─ 构建请求 body:                                      │
│    │    {                                                 │
│    │      "model": "gpt-4o-mini",                         │
│    │      "messages": [...],                              │
│    │      "temperature": 0.7,                             │
│    │      "max_tokens": 2000,                             │
│    │      "tools": [...]                                  │
│    │    }                                                 │
│    │                                                      │
│    ├─ WebClient HTTP POST:                               │
│    │    POST https://api.openai.com/v1/chat/completions  │
│    │    Authorization: Bearer sk-proj-abc...xyz123        │
│    │    Content-Type: application/json                    │
│    │    Timeout: 120s                                     │
│    │                                                      │
│    │    (第1次尝试: 网络超时 → PlatformException)          │
│    │    ↓ Retry 等待 500ms                                │
│    │    (第2次尝试: 成功 270ms)                            │
│    │                                                      │
│    └─ 解析响应:                                           │
│        {                                                  │
│          "id": "chatcmpl-123",                            │
│          "model": "gpt-4o-mini-2024-07-18",               │
│          "choices": [{                                    │
│            "message": {                                   │
│              "role": "assistant",                         │
│              "content": null,                             │
│              "tool_calls": [{                             │
│                "id": "call_xyz",                          │
│                "function": {                              │
│                  "name": "retriever",                     │
│                  "arguments": "{\"query\":\"Q1财报\"}"    │
│                }                                          │
│              }]                                           │
│            },                                             │
│            "finish_reason": "tool_calls"                  │
│          }],                                              │
│          "usage": {                                       │
│            "prompt_tokens": 280,                          │
│            "completion_tokens": 70,                       │
│            "total_tokens": 350                            │
│          }                                                │
│        }                                                  │
│                                                           │
│        parseChatResponse() → ChatResponse {               │
│          model = "gpt-4o-mini-2024-07-18",                │
│          content = null,                                  │
│          toolCalls = [ToolCall{name="retriever", ...}],   │
│          usage = Usage{totalTokens=350}                   │
│        }                                                  │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ⑤ 记录追踪 Step                                           │
│    long latency = System.currentTimeMillis() - start;     │
│    // latency = 270ms (第2次成功)                         │
│                                                           │
│    tracingService.addStep(                                │
│      runId = null,  // 从 ThreadLocal 获取               │
│      name = "model:openai/gpt-4o-mini",                   │
│      type = LLM,                                          │
│      input = "[{role:system,...},{role:user,...}]",       │
│      output = "{tool_calls:[...]}",                       │
│      tokens = 350,                                        │
│      cost = 0.0007,  // 按 token 计价                    │
│      latencyMs = 270                                      │
│    )                                                      │
│                                                           │
│    SQL:                                                   │
│    INSERT INTO trace_step (                               │
│      id, run_id, name, type, input, output, tokens,       │
│      cost, latency_ms, created_at                         │
│    ) VALUES (                                             │
│      UUID(), 'run-abc123', 'model:openai/gpt-4o-mini',    │
│      'LLM', '[{role:system...', '{tool_calls:...', 350,   │
│      0.0007, 270, NOW()                                   │
│    );                                                     │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ⑥ 返回给 Agent                                            │
│    ChatResponse{                                          │
│      toolCalls = [ToolCall{name="retriever", ...}]        │
│    }                                                      │
│                                                           │
│    Agent 解析 tool_calls，执行 Retriever 工具              │
└──────────────────────────────────────────────────────────┘

性能指标：
- 第1次调用失败: 超时 (~5s)
- Retry 等待: 500ms
- 第2次调用成功: 270ms
- 总耗时: ~5.8s (含重试)
- Token 消耗: 350 tokens, $0.0007
```

### 场景 2：流式输出（SSE）

```
┌──────────────────────────────────────────────────────────┐
│ Agent 需要流式输出给用户                                   │
│ modelGateway.chatStream(ChatRequest{...})                 │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ ModelGatewayImpl.chatStream()                             │
│    ├─ resolve provider (同上)                             │
│    ├─ 不走 retry（流式中断无法重试）                       │
│    └─ OpenAiCompatClient.chatStream()                    │
│         WebClient.post().retrieve()                       │
│           .bodyToFlux(String.class)                       │
│           .timeout(120s)                                  │
│                                                           │
│         返回: Flux<String> 流                             │
│           ↓                                               │
│         "data: {\"choices\":[{\"delta\":{\"content\":     │
│                \"Q1\"}}]}\n\n"                            │
│         "data: {\"choices\":[{\"delta\":{\"content\":     │
│                \"营收\"}}]}\n\n"                          │
│         "data: {\"choices\":[{\"delta\":{\"content\":     │
│                \"1亿美元\"}}]}\n\n"                       │
│         "data: [DONE]\n\n"                                │
└────────────────────┬─────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────────┐
│ Controller SSE Endpoint                                   │
│ GET /api/v1/agent/runs/{runId}/stream                    │
│    → 将 Flux<String> 推送给客户端                          │
│    → 客户端实时显示 "Q1营收1亿美元" 逐字符出现              │
└──────────────────────────────────────────────────────────┘

注意：流式调用不记录 TraceStep（无法在流开始前知道 tokens）
```

## 五、与其他子系统的协作

```
┌───────────────────────────────────────────────┐
│   Agent / Workflow / RAG 业务层                │
│  - AgentExecutor                               │
│  - WorkflowLlmNode                             │
│  - Embedder / Reranker                         │
└───────────┬───────────────────────────────────┘
            │ @Autowired
            ▼
┌───────────────────────────────────────────────┐
│   ModelGateway                                 │
│  - chat() / chatStream()                       │
│  - embed() / rerank()                          │
└───────────┬───────────────────────────────────┘
            │
            ├─ ModelProviderService (管理 Provider)
            │    └─ AES/GCM 加密/解密 API Key
            │
            ├─ ProviderClient (HTTP 调用)
            │    └─ OpenAiCompatClient (WebClient)
            │
            ├─ Resilience4j Retry
            │
            └─ TracingService.addStep()
                  ▼
┌───────────────────────────────────────────────┐
│   MySQL (model_provider 表)                   │
│   Tracing (trace_step 表)                     │
└───────────────────────────────────────────────┘

关键集成点：
1. Agent 每次 LLM 调用都走 ModelGateway
2. RAG Embedder 通过 embed() 生成 query vector
3. RAG Reranker 通过 rerank() 重排检索结果
```

## 六、关键设计权衡

1. **OpenAI-compatible 统一协议** — OpenAI API 格式已成事实标准，DeepSeek / 火山 / 通义均兼容。统一协议降低适配成本，一套 Client 覆盖大部分场景。Claude / Gemini 等少数 provider 需单独适配。

2. **同步 chat vs 异步 chatStream** — `chat()` 用 `block()` 等待完整响应，适合 Agent 推理；`chatStream()` 返回 `Flux` 流式推送，适合用户交互。两种模式分离，避免流式语义污染同步调用。

3. **Retry 在 Gateway 而非 Client** — Gateway 层知道业务语义（如 fallback 策略），Client 层只负责 HTTP 调用。流式调用不走 retry（已开始推送 token，重试会重复输出）。

4. **Tracing best-effort** — 追踪失败用 `log.warn` 记录，不抛异常。避免追踪系统故障导致模型调用失败。

5. **API Key 加密落库** — AES/GCM 对称加密（见第七节安全增强），密钥从环境变量 `MODEL_GATEWAY_ENCRYPTION_KEY` 读取。一期不用 Vault，降低部署复杂度；生产环境可替换为 AWS KMS / HashiCorp Vault。

6. **Provider 按 workspace 隔离** — 不同团队可配置不同 API Key、额度、fallback 策略。支持多租户成本分摊。

## 七、安全增强（b4-security-fixes）

### 1. AES/ECB 替换为 AES/GCM（CRITICAL）

**问题**：原实现用 AES/ECB 模式，相同明文生成相同密文，易被模式分析攻击。

**修复**：

```java
// 修复后的 encryptApiKey()
private String encryptApiKey(String plaintext) {
    try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12]; // GCM 标准 IV 长度
        SecureRandom.getInstanceStrong().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        
        SecretKeySpec keySpec = new SecretKeySpec(
            encryptionKey.getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
        
        byte[] encrypted = cipher.doFinal(
            plaintext.getBytes(StandardCharsets.UTF_8));
        
        // IV + encrypted 一起 base64
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, 
                         encrypted.length);
        
        return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
        throw new PlatformException(ErrorCode.INTERNAL_ERROR, 
            "Failed to encrypt API key", e);
    }
}
```

**解密时从密文前 12 字节提取 IV**，用于 GCM 解密。

### 2. API 响应隐藏 apiKeyEncrypted（CRITICAL）

**问题**：Controller 直接返回 `ModelProvider` 实体，暴露 `apiKeyEncrypted` 字段。

**修复**：

```java
public record ProviderResponse(
    String id, String name, String type, String baseUrl,
    String modelsJson, Boolean enabled,
    LocalDateTime createdAt, LocalDateTime updatedAt
) {
    static ProviderResponse from(ModelProvider p) {
        return new ProviderResponse(
            p.getId(), p.getName(), p.getType(), p.getBaseUrl(),
            p.getModelsJson(), p.getEnabled(), 
            p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}

// Controller 返回 DTO
@GetMapping("/{id}")
public ApiResponse<ProviderResponse> getById(@PathVariable String id) {
    return ApiResponse.ok(ProviderResponse.from(service.getById(id)));
}
```

### 3. 使用配置的超时而非硬编码（HIGH）

**问题**：`OpenAiCompatClient` 硬编码 `TIMEOUT = 120s`。

**修复**：注入 `ModelGatewayProperties`，从配置读取 `timeoutSeconds`。

### 4. baseUrl 校验防止 SSRF（HIGH）

**问题**：用户可配置任意 baseUrl，可能指向内网服务（如 `http://169.254.169.254/`）。

**修复**：

```java
private void validateBaseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
        throw new PlatformException(ErrorCode.INVALID_REQUEST, 
            "Base URL is required");
    }
    try {
        URI uri = new URI(baseUrl);
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, 
                "Base URL must use http or https protocol");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, 
                "Invalid base URL");
        }
        // 可选：禁止内网 IP (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
    } catch (URISyntaxException e) {
        throw new PlatformException(ErrorCode.INVALID_REQUEST, 
            "Invalid base URL format: " + e.getMessage());
    }
}

// create() 和 update() 方法中调用
validateBaseUrl(cmd.baseUrl());
```

### 5. 返回不可变集合（HIGH）

**问题**：`parseChatResponse()` 返回 `List<ToolCall>`，调用方可能修改。

**修复**：

```java
return new ChatResponse(
    model, content, role, 
    toolCalls.isEmpty() ? null : List.copyOf(toolCalls), // 不可变
    usage
);
```

### 6. Controller 输入长度校验（HIGH）

**修复**：

```java
public record CreateRequest(
    @NotBlank @Size(max = 64) String name,
    @NotBlank @Size(max = 32) String type,
    @NotBlank @Size(max = 512) String baseUrl,
    @NotBlank @Size(max = 256) String apiKey,
    @Size(max = 10000) String modelsJson
) {}
```

## 八、横切关注点

| 关注点 | 触发位置 | 实现方式 |
|--------|---------|---------|
| **多租户隔离** | Provider 查询 | `ModelProviderMapper` 标注 `@WorkspaceAware`，自动注入 `WHERE workspace_id = ?` |
| **重试** | chat / embed / rerank | Resilience4j Retry，maxAttempts=3，waitDuration=500ms 指数退避 |
| **超时** | 所有 HTTP 调用 | WebClient timeout=120s（可配置） |
| **追踪** | 每次模型调用 | `TracingService.addStep(type=LLM)`，best-effort 不阻塞 |
| **加密** | API Key 存储 | AES/GCM 对称加密，IV 随机生成 |
| **流式** | chatStream | Reactor `Flux<String>` SSE 推送 |

## 九、未来扩展点

1. **Fallback 策略** — `defaultChat` 调用失败后自动切换到 `fallbackChat`（如 OpenAI → DeepSeek）。

2. **速率限制** — 按 workspace / provider 限制 QPM / QPD，防止额度耗尽。

3. **成本预警** — 单次调用成本超过阈值（如 $1）发送告警。

4. **模型路由策略** — 按任务类型自动选模型（简单任务用 gpt-4o-mini，复杂任务用 gpt-4o）。

5. **缓存语义等价查询** — 相同 prompt + 参数的查询复用缓存结果（语义 hash）。

6. **支持更多 Provider** — 集成 Anthropic SDK（Claude）、Google Vertex AI（Gemini）等非 OpenAI-compatible 的 API。
