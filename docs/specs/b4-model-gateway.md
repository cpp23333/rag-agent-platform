# B4 Spec: ModelGateway

## Context

ModelGateway 是 Platform Core 的统一模型调用子系统（架构设计 §3.1）。它为 Agent、Workflow、RAG 提供统一的 LLM / Embedding / Rerank 入口，封装 provider 路由、key 管理、retry、限流和调用计费。

## 目标

- 统一接口：`chat` / `chatStream` / `embed` / `rerank`
- 多 provider 支持：OpenAI / DeepSeek / Claude / 火山等（均走 OpenAI-compatible API）
- Provider 配置持久化（DB）+ YAML 初始化
- Resilience4j retry + 超时
- 调用自动记录到 Tracing（TraceStep）
- 流式输出（Reactor Flux）

## 范围

`platform-core` 模块（核心逻辑）+ `server` 模块（REST endpoint 用于测试/管理）。

---

## File Structure

```
platform-core/src/main/resources/db/changelog/changes/
  010-model-provider.yaml

platform-core/src/main/java/io/kyligence/ragagent/core/model/
  ModelProvider.java              # DB 实体
  ModelProviderMapper.java
  ModelType.java                  # CHAT, EMBEDDING, RERANK, VISION
  ChatRequest.java                # 请求 DTO
  ChatResponse.java               # 响应 DTO
  ChatMessage.java
  EmbeddingRequest.java
  EmbeddingResponse.java
  RerankRequest.java
  RerankResponse.java
  ModelGateway.java               # 核心接口
  ModelGatewayImpl.java           # 实现
  ModelProviderService.java       # provider CRUD
  ModelProviderServiceImpl.java
  ProviderClient.java             # 底层 HTTP 调用抽象
  OpenAiCompatClient.java         # OpenAI-compatible 实现（WebClient）
  ModelGatewayProperties.java     # 路由配置绑定

platform-core/src/test/java/io/kyligence/ragagent/core/model/
  ModelGatewayImplTest.java
  OpenAiCompatClientTest.java
  ModelProviderServiceImplTest.java

server/src/main/java/io/kyligence/ragagent/server/controller/
  ModelProviderController.java    # /api/v1/workspaces/{id}/model-providers
```

---

## Phase 1: DB Schema

### File: `010-model-provider.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 010-model-provider
      author: ragagent
      changes:
        - createTable:
            tableName: model_provider
            columns:
              - column: { name: id, type: VARCHAR(36), constraints: { primaryKey: true, nullable: false } }
              - column: { name: workspace_id, type: BIGINT, constraints: { nullable: false } }
              - column: { name: name, type: VARCHAR(64), constraints: { nullable: false } }
              - column: { name: type, type: VARCHAR(32), constraints: { nullable: false } }
              - column: { name: base_url, type: VARCHAR(512), constraints: { nullable: false } }
              - column: { name: api_key_encrypted, type: VARCHAR(1024), constraints: { nullable: false } }
              - column: { name: models_json, type: TEXT }
              - column: { name: enabled, type: BOOLEAN, defaultValueBoolean: true, constraints: { nullable: false } }
              - column: { name: created_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: updated_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - addUniqueConstraint:
            tableName: model_provider
            columnNames: "workspace_id, name"
            constraintName: uq_provider_ws_name
        - createIndex:
            tableName: model_provider
            indexName: idx_provider_workspace
            columns:
              - column: { name: workspace_id }
```

设计说明：
- `type`：`openai` / `openai-compat` / `claude` / `azure-openai`
- `models_json`：JSON 对象 `{"chat":["gpt-4o","gpt-4o-mini"],"embedding":["text-embedding-3-small"]}`
- `api_key_encrypted`：一期用 AES-256 对称加密，密钥从环境变量读取

---

## Phase 2: Domain DTOs

### File: `core/model/ModelType.java`

```java
package io.kyligence.ragagent.core.model;

public enum ModelType {
    CHAT, EMBEDDING, RERANK, VISION
}
```

### File: `core/model/ChatMessage.java`

```java
package io.kyligence.ragagent.core.model;

public record ChatMessage(String role, String content, String name) {
    public static ChatMessage system(String content) { return new ChatMessage("system", content, null); }
    public static ChatMessage user(String content) { return new ChatMessage("user", content, null); }
    public static ChatMessage assistant(String content) { return new ChatMessage("assistant", content, null); }
    public static ChatMessage tool(String content, String name) { return new ChatMessage("tool", content, name); }
}
```

### File: `core/model/ChatRequest.java`

```java
package io.kyligence.ragagent.core.model;

import java.util.List;
import java.util.Map;

public record ChatRequest(
        String model,               // e.g. "openai/gpt-4o-mini" or null for default
        List<ChatMessage> messages,
        Double temperature,
        Integer maxTokens,
        List<Map<String, Object>> tools,   // OpenAI function-calling tool defs
        Map<String, Object> extraParams    // provider-specific overrides
) {
    public ChatRequest(String model, List<ChatMessage> messages) {
        this(model, messages, null, null, null, null);
    }
}
```

### File: `core/model/ChatResponse.java`

```java
package io.kyligence.ragagent.core.model;

import java.util.List;
import java.util.Map;

public record ChatResponse(
        String id,
        String model,
        String content,
        String finishReason,
        List<ToolCall> toolCalls,
        Usage usage
) {
    public record ToolCall(String id, String name, String arguments) {}
    public record Usage(int promptTokens, int completionTokens, int totalTokens) {}
}
```

### File: `core/model/EmbeddingRequest.java`

```java
package io.kyligence.ragagent.core.model;

import java.util.List;

public record EmbeddingRequest(
        String model,          // null = use default_embedding
        List<String> inputs
) {}
```

### File: `core/model/EmbeddingResponse.java`

```java
package io.kyligence.ragagent.core.model;

import java.util.List;

public record EmbeddingResponse(
        String model,
        List<float[]> embeddings,
        int totalTokens
) {}
```

### File: `core/model/RerankRequest.java`

```java
package io.kyligence.ragagent.core.model;

import java.util.List;

public record RerankRequest(
        String model,
        String query,
        List<String> documents,
        int topN
) {}
```

### File: `core/model/RerankResponse.java`

```java
package io.kyligence.ragagent.core.model;

import java.util.List;

public record RerankResponse(
        String model,
        List<ScoredDocument> results
) {
    public record ScoredDocument(int index, double score, String text) {}
}
```

---

## Phase 3: Entity & Mapper

### File: `core/model/ModelProvider.java`

```java
package io.kyligence.ragagent.core.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("model_provider")
public class ModelProvider {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long workspaceId;
    private String name;
    private String type;          // openai, openai-compat, claude, azure-openai
    private String baseUrl;
    private String apiKeyEncrypted;
    private String modelsJson;    // {"chat":["gpt-4o"],"embedding":["text-embedding-3-small"]}
    private Boolean enabled;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

### File: `core/model/ModelProviderMapper.java`

```java
package io.kyligence.ragagent.core.model;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@WorkspaceAware
public interface ModelProviderMapper extends BaseMapper<ModelProvider> {
}
```

---

## Phase 4: Provider Client（底层 HTTP 调用）

### File: `core/model/ProviderClient.java`

```java
package io.kyligence.ragagent.core.model;

import reactor.core.publisher.Flux;

public interface ProviderClient {

    ChatResponse chat(String baseUrl, String apiKey, ChatRequest request);

    Flux<String> chatStream(String baseUrl, String apiKey, ChatRequest request);

    EmbeddingResponse embed(String baseUrl, String apiKey, EmbeddingRequest request);

    RerankResponse rerank(String baseUrl, String apiKey, RerankRequest request);
}
```

### File: `core/model/OpenAiCompatClient.java`

使用 Spring WebClient 调用 OpenAI-compatible API。

```java
package io.kyligence.ragagent.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiCompatClient implements ProviderClient {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    @Override
    public ChatResponse chat(String baseUrl, String apiKey, ChatRequest request) {
        ObjectNode body = buildChatBody(request, false);
        JsonNode resp = post(baseUrl, "/chat/completions", apiKey, body);
        return parseChatResponse(resp);
    }

    @Override
    public Flux<String> chatStream(String baseUrl, String apiKey, ChatRequest request) {
        ObjectNode body = buildChatBody(request, true);
        return webClientBuilder.build()
                .post()
                .uri(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(TIMEOUT);
    }

    @Override
    public EmbeddingResponse embed(String baseUrl, String apiKey, EmbeddingRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        ArrayNode inputs = body.putArray("input");
        request.inputs().forEach(inputs::add);

        JsonNode resp = post(baseUrl, "/embeddings", apiKey, body);
        return parseEmbeddingResponse(resp, request.model());
    }

    @Override
    public RerankResponse rerank(String baseUrl, String apiKey, RerankRequest request) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", request.model());
        body.put("query", request.query());
        ArrayNode docs = body.putArray("documents");
        request.documents().forEach(docs::add);
        body.put("top_n", request.topN());

        JsonNode resp = post(baseUrl, "/rerank", apiKey, body);
        return parseRerankResponse(resp, request);
    }

    // ── private helpers ──

    private ObjectNode buildChatBody(ChatRequest req, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        String model = req.model() != null ? resolveModelName(req.model()) : "gpt-4o-mini";
        body.put("model", model);
        body.put("stream", stream);
        if (req.temperature() != null) body.put("temperature", req.temperature());
        if (req.maxTokens() != null) body.put("max_tokens", req.maxTokens());

        ArrayNode msgs = body.putArray("messages");
        for (ChatMessage m : req.messages()) {
            ObjectNode msg = msgs.addObject();
            msg.put("role", m.role());
            msg.put("content", m.content());
            if (m.name() != null) msg.put("name", m.name());
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            body.set("tools", objectMapper.valueToTree(req.tools()));
        }
        return body;
    }

    private JsonNode post(String baseUrl, String path, String apiKey, ObjectNode body) {
        try {
            String response = webClientBuilder.build()
                    .post()
                    .uri(baseUrl + path)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(TIMEOUT)
                    .block();
            return objectMapper.readTree(response);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.MODEL_CALL_FAILED,
                    "Model API call failed: " + e.getMessage(), e);
        }
    }

    private String resolveModelName(String qualifiedModel) {
        // "openai/gpt-4o-mini" -> "gpt-4o-mini"
        int slash = qualifiedModel.indexOf('/');
        return slash >= 0 ? qualifiedModel.substring(slash + 1) : qualifiedModel;
    }

    private ChatResponse parseChatResponse(JsonNode resp) {
        JsonNode choice = resp.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText(null);
        String finishReason = choice.path("finish_reason").asText(null);

        List<ChatResponse.ToolCall> toolCalls = new ArrayList<>();
        if (message.has("tool_calls")) {
            for (JsonNode tc : message.path("tool_calls")) {
                toolCalls.add(new ChatResponse.ToolCall(
                        tc.path("id").asText(),
                        tc.path("function").path("name").asText(),
                        tc.path("function").path("arguments").asText()));
            }
        }

        JsonNode usage = resp.path("usage");
        ChatResponse.Usage u = new ChatResponse.Usage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0));

        return new ChatResponse(
                resp.path("id").asText(null),
                resp.path("model").asText(null),
                content, finishReason,
                toolCalls.isEmpty() ? null : toolCalls, u);
    }

    private EmbeddingResponse parseEmbeddingResponse(JsonNode resp, String model) {
        List<float[]> embeddings = new ArrayList<>();
        for (JsonNode item : resp.path("data")) {
            JsonNode emb = item.path("embedding");
            float[] vec = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) vec[i] = (float) emb.get(i).asDouble();
            embeddings.add(vec);
        }
        int tokens = resp.path("usage").path("total_tokens").asInt(0);
        return new EmbeddingResponse(model, embeddings, tokens);
    }

    private RerankResponse parseRerankResponse(JsonNode resp, RerankRequest req) {
        List<RerankResponse.ScoredDocument> results = new ArrayList<>();
        for (JsonNode item : resp.path("results")) {
            int idx = item.path("index").asInt();
            double score = item.path("relevance_score").asDouble();
            String text = idx < req.documents().size() ? req.documents().get(idx) : "";
            results.add(new RerankResponse.ScoredDocument(idx, score, text));
        }
        return new RerankResponse(req.model(), results);
    }
}
```

---

## Phase 5: Gateway Interface & Implementation

### File: `core/model/ModelGatewayProperties.java`

路由配置，绑定 `ragagent.model-gateway.*`。

```java
package io.kyligence.ragagent.core.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ragagent.model-gateway")
public class ModelGatewayProperties {
    private String defaultChat = "openai/gpt-4o-mini";
    private String fallbackChat;
    private String defaultEmbedding = "openai/text-embedding-3-small";
    private String defaultRerank;
    private int retryMaxAttempts = 3;
    private long timeoutSeconds = 120;
}
```

### File: `core/model/ModelGateway.java`

```java
package io.kyligence.ragagent.core.model;

import reactor.core.publisher.Flux;

public interface ModelGateway {

    ChatResponse chat(ChatRequest request);

    Flux<String> chatStream(ChatRequest request);

    EmbeddingResponse embed(EmbeddingRequest request);

    RerankResponse rerank(RerankRequest request);
}
```

### File: `core/model/ModelGatewayImpl.java`

```java
package io.kyligence.ragagent.core.model;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.kyligence.ragagent.core.tracing.StepType;
import io.kyligence.ragagent.core.tracing.TracingService;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Service
public class ModelGatewayImpl implements ModelGateway {

    private final ModelProviderService providerService;
    private final ProviderClient client;
    private final ModelGatewayProperties props;
    private final TracingService tracingService;
    private final Retry retry;

    public ModelGatewayImpl(ModelProviderService providerService,
                            ProviderClient client,
                            ModelGatewayProperties props,
                            TracingService tracingService) {
        this.providerService = providerService;
        this.client = client;
        this.props = props;
        this.tracingService = tracingService;
        this.retry = Retry.of("model-gateway", RetryConfig.custom()
                .maxAttempts(props.getRetryMaxAttempts())
                .waitDuration(Duration.ofMillis(500))
                .retryExceptions(PlatformException.class)
                .build());
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String modelRef = request.model() != null ? request.model() : props.getDefaultChat();
        ResolvedProvider resolved = resolve(modelRef);
        long start = System.currentTimeMillis();

        ChatResponse resp = withRetry(() ->
                client.chat(resolved.baseUrl(), resolved.apiKey(), request));

        long latency = System.currentTimeMillis() - start;
        recordStep(modelRef, request.messages().toString(), resp.content(),
                resp.usage() != null ? resp.usage().totalTokens() : 0, latency);
        return resp;
    }

    @Override
    public Flux<String> chatStream(ChatRequest request) {
        String modelRef = request.model() != null ? request.model() : props.getDefaultChat();
        ResolvedProvider resolved = resolve(modelRef);
        return client.chatStream(resolved.baseUrl(), resolved.apiKey(), request);
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        String modelRef = request.model() != null ? request.model() : props.getDefaultEmbedding();
        ResolvedProvider resolved = resolve(modelRef);
        long start = System.currentTimeMillis();

        EmbeddingResponse resp = withRetry(() ->
                client.embed(resolved.baseUrl(), resolved.apiKey(), request));

        long latency = System.currentTimeMillis() - start;
        recordStep(modelRef, request.inputs().size() + " inputs", "embeddings",
                resp.totalTokens(), latency);
        return resp;
    }

    @Override
    public RerankResponse rerank(RerankRequest request) {
        String modelRef = request.model() != null ? request.model() : props.getDefaultRerank();
        if (modelRef == null) {
            throw new PlatformException(ErrorCode.MODEL_PROVIDER_NOT_FOUND,
                    "No default rerank model configured");
        }
        ResolvedProvider resolved = resolve(modelRef);
        long start = System.currentTimeMillis();

        RerankResponse resp = withRetry(() ->
                client.rerank(resolved.baseUrl(), resolved.apiKey(), request));

        long latency = System.currentTimeMillis() - start;
        recordStep(modelRef, request.query(), resp.results().size() + " results", 0, latency);
        return resp;
    }

    // ── private ──

    private record ResolvedProvider(String baseUrl, String apiKey) {}

    private ResolvedProvider resolve(String modelRef) {
        // modelRef format: "providerName/modelName"
        int slash = modelRef.indexOf('/');
        if (slash < 0) {
            throw new PlatformException(ErrorCode.MODEL_PROVIDER_NOT_FOUND,
                    "Invalid model reference (expected provider/model): " + modelRef);
        }
        String providerName = modelRef.substring(0, slash);
        ModelProvider provider = providerService.getByName(providerName);
        if (provider == null || !provider.getEnabled()) {
            throw new PlatformException(ErrorCode.MODEL_PROVIDER_NOT_FOUND,
                    "Provider not found or disabled: " + providerName);
        }
        String apiKey = providerService.decryptApiKey(provider.getApiKeyEncrypted());
        return new ResolvedProvider(provider.getBaseUrl(), apiKey);
    }

    private <T> T withRetry(Supplier<T> supplier) {
        return Retry.decorateSupplier(retry, supplier).get();
    }

    private void recordStep(String model, String input, String output,
                            int tokens, long latencyMs) {
        try {
            // Tracing is best-effort; don't fail the call if tracing fails
            tracingService.addStep(null, "model:" + model, StepType.LLM,
                    truncate(input, 2000), truncate(output, 2000),
                    tokens, BigDecimal.ZERO, latencyMs);
        } catch (Exception e) {
            log.warn("Failed to record tracing step: {}", e.getMessage());
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
```

---

## Phase 6: Provider Service (CRUD)

### File: `core/model/ModelProviderService.java`

```java
package io.kyligence.ragagent.core.model;

import java.util.List;

public interface ModelProviderService {

    ModelProvider create(Long workspaceId, CreateProviderCommand cmd);

    ModelProvider update(String id, UpdateProviderCommand cmd);

    ModelProvider getByName(String name);

    ModelProvider getById(String id);

    List<ModelProvider> list(Long workspaceId);

    void delete(String id);

    String decryptApiKey(String encrypted);

    record CreateProviderCommand(
            String name, String type, String baseUrl,
            String apiKey, String modelsJson) {}

    record UpdateProviderCommand(
            String baseUrl, String apiKey,
            String modelsJson, Boolean enabled) {}
}
```

### File: `core/model/ModelProviderServiceImpl.java`

```java
package io.kyligence.ragagent.core.model;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProviderServiceImpl implements ModelProviderService {

    private final ModelProviderMapper mapper;

    @Value("${ragagent.model-gateway.encryption-key:default-key-change-in-prod!}")
    private String encryptionKey;

    @Override
    @Transactional
    public ModelProvider create(Long workspaceId, CreateProviderCommand cmd) {
        ModelProvider p = new ModelProvider();
        p.setWorkspaceId(workspaceId);
        p.setName(cmd.name());
        p.setType(cmd.type());
        p.setBaseUrl(cmd.baseUrl());
        p.setApiKeyEncrypted(encryptApiKey(cmd.apiKey()));
        p.setModelsJson(cmd.modelsJson());
        p.setEnabled(true);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.insert(p);
        return p;
    }

    @Override
    @Transactional
    public ModelProvider update(String id, UpdateProviderCommand cmd) {
        ModelProvider p = mapper.selectById(id);
        if (p == null) throw new PlatformException(ErrorCode.MODEL_PROVIDER_NOT_FOUND,
                "Provider not found: " + id);
        if (cmd.baseUrl() != null) p.setBaseUrl(cmd.baseUrl());
        if (cmd.apiKey() != null) p.setApiKeyEncrypted(encryptApiKey(cmd.apiKey()));
        if (cmd.modelsJson() != null) p.setModelsJson(cmd.modelsJson());
        if (cmd.enabled() != null) p.setEnabled(cmd.enabled());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(p);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public ModelProvider getByName(String name) {
        return mapper.selectOne(
                Wrappers.<ModelProvider>lambdaQuery()
                        .eq(ModelProvider::getName, name)
                        .eq(ModelProvider::getEnabled, true));
    }

    @Override
    @Transactional(readOnly = true)
    public ModelProvider getById(String id) {
        return mapper.selectById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelProvider> list(Long workspaceId) {
        return mapper.selectList(
                Wrappers.<ModelProvider>lambdaQuery()
                        .eq(ModelProvider::getWorkspaceId, workspaceId)
                        .orderByAsc(ModelProvider::getName));
    }

    @Override
    @Transactional
    public void delete(String id) {
        mapper.deleteById(id);
    }

    @Override
    public String decryptApiKey(String encrypted) {
        try {
            byte[] key = padKey(encryptionKey);
            SecretKeySpec spec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, spec);
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to decrypt API key", e);
        }
    }

    private String encryptApiKey(String plaintext) {
        try {
            byte[] key = padKey(encryptionKey);
            SecretKeySpec spec = new SecretKeySpec(key, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, spec);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Failed to encrypt API key", e);
        }
    }

    private byte[] padKey(String key) {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[32]; // AES-256
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, 32));
        return padded;
    }
}
```

---

## Phase 7: REST Controller

### File: `server/.../controller/ModelProviderController.java`

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.model.ModelProvider;
import io.kyligence.ragagent.core.model.ModelProviderService;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/model-providers")
public class ModelProviderController {

    public record CreateRequest(
            @NotBlank String name,
            @NotBlank String type,
            @NotBlank String baseUrl,
            @NotBlank String apiKey,
            String modelsJson) {}

    public record UpdateRequest(
            String baseUrl, String apiKey,
            String modelsJson, Boolean enabled) {}

    private final ModelProviderService service;

    public ModelProviderController(ModelProviderService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ModelProvider>> list(@PathVariable Long workspaceId) {
        return ApiResponse.ok(service.list(workspaceId));
    }

    @PostMapping
    public ApiResponse<ModelProvider> create(@PathVariable Long workspaceId,
                                             @Valid @RequestBody CreateRequest req) {
        return ApiResponse.ok(service.create(workspaceId,
                new ModelProviderService.CreateProviderCommand(
                        req.name(), req.type(), req.baseUrl(),
                        req.apiKey(), req.modelsJson())));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelProvider> update(@PathVariable String id,
                                             @RequestBody UpdateRequest req) {
        return ApiResponse.ok(service.update(id,
                new ModelProviderService.UpdateProviderCommand(
                        req.baseUrl(), req.apiKey(),
                        req.modelsJson(), req.enabled())));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ApiResponse.ok(null);
    }
}
```

---

## Phase 8: Tests

### File: `core/model/ModelGatewayImplTest.java`

```java
package io.kyligence.ragagent.core.model;

import io.kyligence.ragagent.core.tracing.TracingService;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelGatewayImplTest {

    @Mock private ModelProviderService providerService;
    @Mock private ProviderClient client;
    @Mock private TracingService tracingService;

    private ModelGatewayImpl gateway;

    @BeforeEach
    void setUp() {
        ModelGatewayProperties props = new ModelGatewayProperties();
        props.setDefaultChat("openai/gpt-4o-mini");
        props.setDefaultEmbedding("openai/text-embedding-3-small");
        props.setRetryMaxAttempts(1);
        gateway = new ModelGatewayImpl(providerService, client, props, tracingService);
    }

    @Test
    void chatResolvesProviderAndCallsClient() {
        ModelProvider provider = new ModelProvider();
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyEncrypted("enc");
        provider.setEnabled(true);
        when(providerService.getByName("openai")).thenReturn(provider);
        when(providerService.decryptApiKey("enc")).thenReturn("sk-test");

        ChatResponse expected = new ChatResponse("id", "gpt-4o-mini", "hello",
                "stop", null, new ChatResponse.Usage(10, 5, 15));
        when(client.chat(eq("https://api.openai.com/v1"), eq("sk-test"), any()))
                .thenReturn(expected);

        ChatRequest req = new ChatRequest("openai/gpt-4o-mini",
                List.of(ChatMessage.user("hi")));
        ChatResponse resp = gateway.chat(req);

        assertThat(resp.content()).isEqualTo("hello");
        verify(client).chat(any(), any(), any());
    }

    @Test
    void chatUsesDefaultModelWhenNull() {
        ModelProvider provider = new ModelProvider();
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyEncrypted("enc");
        provider.setEnabled(true);
        when(providerService.getByName("openai")).thenReturn(provider);
        when(providerService.decryptApiKey("enc")).thenReturn("sk-test");
        when(client.chat(any(), any(), any())).thenReturn(
                new ChatResponse("id", "gpt-4o-mini", "ok", "stop", null,
                        new ChatResponse.Usage(1, 1, 2)));

        ChatRequest req = new ChatRequest(null, List.of(ChatMessage.user("hi")));
        ChatResponse resp = gateway.chat(req);
        assertThat(resp.content()).isEqualTo("ok");
    }

    @Test
    void chatThrowsWhenProviderNotFound() {
        when(providerService.getByName("unknown")).thenReturn(null);

        ChatRequest req = new ChatRequest("unknown/model",
                List.of(ChatMessage.user("hi")));
        assertThatThrownBy(() -> gateway.chat(req))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("not found or disabled");
    }

    @Test
    void embedResolvesAndCalls() {
        ModelProvider provider = new ModelProvider();
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setApiKeyEncrypted("enc");
        provider.setEnabled(true);
        when(providerService.getByName("openai")).thenReturn(provider);
        when(providerService.decryptApiKey("enc")).thenReturn("sk-test");
        when(client.embed(any(), any(), any())).thenReturn(
                new EmbeddingResponse("text-embedding-3-small",
                        List.of(new float[]{0.1f, 0.2f}), 10));

        EmbeddingRequest req = new EmbeddingRequest(null, List.of("hello"));
        EmbeddingResponse resp = gateway.embed(req);
        assertThat(resp.embeddings()).hasSize(1);
    }
}
```

### File: `core/model/ModelProviderServiceImplTest.java`

```java
package io.kyligence.ragagent.core.model;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModelProviderServiceImplTest {

    @Mock private ModelProviderMapper mapper;
    private ModelProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ModelProviderServiceImpl(mapper);
        ReflectionTestUtils.setField(service, "encryptionKey",
                "test-encryption-key-32-chars!!");
    }

    @Test
    void createInsertsProvider() {
        when(mapper.insert(any())).thenReturn(1);
        ModelProvider result = service.create(1L,
                new ModelProviderService.CreateProviderCommand(
                        "openai", "openai", "https://api.openai.com/v1",
                        "sk-test", "{\"chat\":[\"gpt-4o\"]}"));
        assertThat(result.getName()).isEqualTo("openai");
        assertThat(result.getApiKeyEncrypted()).isNotEqualTo("sk-test");
        verify(mapper).insert(any());
    }

    @Test
    void encryptDecryptRoundTrip() {
        String original = "sk-my-secret-key-12345";
        ReflectionTestUtils.setField(service, "encryptionKey",
                "test-encryption-key-32-chars!!");
        // Use reflection to call private method
        String encrypted = (String) ReflectionTestUtils.invokeMethod(
                service, "encryptApiKey", original);
        String decrypted = service.decryptApiKey(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void updateThrowsWhenNotFound() {
        when(mapper.selectById("missing")).thenReturn(null);
        assertThatThrownBy(() -> service.update("missing",
                new ModelProviderService.UpdateProviderCommand(null, null, null, null)))
                .isInstanceOf(PlatformException.class);
    }
}
```

---

## Phase 9: Configuration (application.yml additions)

Add to `server/src/main/resources/application.yml`:

```yaml
ragagent:
  model-gateway:
    default-chat: openai/gpt-4o-mini
    fallback-chat: deepseek/deepseek-chat
    default-embedding: openai/text-embedding-3-small
    default-rerank: null
    retry-max-attempts: 3
    timeout-seconds: 120
    encryption-key: ${MODEL_GATEWAY_ENCRYPTION_KEY:change-me-in-prod-32-chars-min!}
```

---

## Acceptance Criteria

1. `mvn -pl platform-core -am compile` 通过
2. `mvn -pl platform-core -Dtest=ModelGatewayImplTest,ModelProviderServiceImplTest test` 全绿
3. `mvn -pl server -am compile` 通过
4. 已有测试不被破坏
5. Liquibase `010-model-provider.yaml` 被 master changelog 自动加载
6. API key 加密存储，明文不落库

## Dependencies

- `spring-boot-starter-webflux` 已在 B1 Phase 0 引入（WebClient）✓
- `resilience4j-retry` 已在 B1 Phase 0 引入 ✓
- `jackson-databind` 已在 B1 Phase 0 引入 ✓
- `ErrorCode.MODEL_PROVIDER_NOT_FOUND` / `MODEL_CALL_FAILED` / `MODEL_TIMEOUT` 已存在 ✓
- `TracingService` 已在 B1 Phase 1 实现 ✓

## 设计决策

| 决策 | 理由 |
|------|------|
| OpenAI-compatible 统一协议 | DeepSeek/火山/通义等均兼容 OpenAI API 格式 |
| AES-256 对称加密 API key | 一期简单可控；生产可替换为 Vault |
| Retry 在 Gateway 层而非 Client 层 | Gateway 知道业务语义（fallback 策略） |
| Tracing best-effort | 不因 tracing 失败阻塞模型调用 |
| 流式不走 retry | SSE 流中断后重试语义复杂，一期不做 |
| Provider 按 workspace 隔离 | 不同团队可配不同 key/额度 |
```