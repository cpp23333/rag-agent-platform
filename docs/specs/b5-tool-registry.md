# B5 Spec: ToolRegistry

## Context

ToolRegistry 是 Platform Core 的工具注册中心（架构设计 §3.2）。Agent 和 Workflow 通过它发现和调用 Tool。支持四种实现类型：`JAVA_BEAN`（Spring Bean）、`HTTP`（外部 HTTP API）、`WORKFLOW_REF`（包装 Workflow）、`RETRIEVER_REF`（包装 Retriever）。一期实现 JAVA_BEAN + HTTP 两种，WORKFLOW_REF / RETRIEVER_REF 留接口占位。

## 目标

- 统一 `Tool` 接口，支持 schema 描述（供 LLM function-calling 使用）
- 支持 JAVA_BEAN（Spring Bean 自动注册）和 HTTP（YAML/DB 配置）两种实现
- `ToolRegistry` 提供 register / find / invoke 能力
- 工具定义可持久化到 DB（HTTP 类型）
- WORKFLOW_REF / RETRIEVER_REF 一期返回 UnsupportedOperationException

## 范围

`platform-core` 模块 + `server` 模块（REST controller 管理 HTTP tool）。

---

## File Structure

```
platform-core/src/main/resources/db/changelog/changes/
  011-tool-def.yaml

platform-core/src/main/java/io/kyligence/ragagent/core/tool/
  ToolImplType.java
  ToolInput.java
  ToolOutput.java
  ToolContext.java
  Tool.java                       # 接口
  ToolResult.java
  ToolDef.java                    # DB 实体
  ToolDefMapper.java
  JavaBeanTool.java               # JAVA_BEAN 适配器基类
  HttpTool.java                   # HTTP 实现
  ToolRegistry.java               # 接口
  ToolRegistryImpl.java

platform-core/src/test/java/io/kyligence/ragagent/core/tool/
  ToolRegistryImplTest.java
  HttpToolTest.java

server/src/main/java/io/kyligence/ragagent/server/controller/
  ToolController.java
```

---

## Phase 1: DB Schema

### File: `011-tool-def.yaml`

```yaml
databaseChangeLog:
  - changeSet:
      id: 011-tool-def
      author: ragagent
      changes:
        - createTable:
            tableName: tool_def
            columns:
              - column: { name: id, type: VARCHAR(36), constraints: { primaryKey: true, nullable: false } }
              - column: { name: workspace_id, type: BIGINT, constraints: { nullable: false } }
              - column: { name: name, type: VARCHAR(128), constraints: { nullable: false } }
              - column: { name: description, type: VARCHAR(512) }
              - column: { name: impl_type, type: VARCHAR(32), constraints: { nullable: false } }
              - column: { name: input_schema_json, type: TEXT }
              - column: { name: output_schema_json, type: TEXT }
              - column: { name: config_json, type: TEXT }
              - column: { name: enabled, type: BOOLEAN, defaultValueBoolean: true, constraints: { nullable: false } }
              - column: { name: created_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
              - column: { name: updated_at, type: DATETIME, defaultValueComputed: "CURRENT_TIMESTAMP", constraints: { nullable: false } }
        - addUniqueConstraint:
            tableName: tool_def
            columnNames: "workspace_id, name"
            constraintName: uq_tool_ws_name
        - createIndex:
            tableName: tool_def
            indexName: idx_tool_workspace
            columns:
              - column: { name: workspace_id }
```

设计说明：
- `impl_type`：`JAVA_BEAN` / `HTTP` / `WORKFLOW_REF` / `RETRIEVER_REF`
- `config_json`（HTTP type）：`{"url":"...","method":"POST","headers":{},"timeout_seconds":30}`
- JAVA_BEAN type 不存 DB，由 Spring 容器扫描注册；`tool_def` 仅存 HTTP/WORKFLOW_REF/RETRIEVER_REF 类型

---

## Phase 2: Core Domain

### File: `core/tool/ToolImplType.java`

```java
package io.kyligence.ragagent.core.tool;

public enum ToolImplType {
    JAVA_BEAN, HTTP, WORKFLOW_REF, RETRIEVER_REF
}
```

### File: `core/tool/ToolInput.java`

```java
package io.kyligence.ragagent.core.tool;

import java.util.Map;

public record ToolInput(Map<String, Object> params) {
    public Object get(String key) { return params.get(key); }
    public String getString(String key) {
        Object v = params.get(key);
        return v == null ? null : v.toString();
    }
}
```

### File: `core/tool/ToolOutput.java`

```java
package io.kyligence.ragagent.core.tool;

import java.util.Map;

public record ToolOutput(Object value, Map<String, Object> metadata) {
    public static ToolOutput of(Object value) { return new ToolOutput(value, Map.of()); }
}
```

### File: `core/tool/ToolContext.java`

```java
package io.kyligence.ragagent.core.tool;

public record ToolContext(String runId, String workspaceId, Long userId) {}
```

### File: `core/tool/ToolResult.java`

```java
package io.kyligence.ragagent.core.tool;

public record ToolResult(boolean success, ToolOutput output, String errorMessage) {

    public static ToolResult ok(Object value) {
        return new ToolResult(true, ToolOutput.of(value), null);
    }

    public static ToolResult failure(String error) {
        return new ToolResult(false, null, error);
    }
}
```

### File: `core/tool/Tool.java`

```java
package io.kyligence.ragagent.core.tool;

public interface Tool {

    String name();

    String description();

    /** JSON Schema string describing the input parameters (for LLM function-calling). */
    String inputSchemaJson();

    ToolResult invoke(ToolInput input, ToolContext ctx);
}
```

### File: `core/tool/JavaBeanTool.java`

Spring Bean 实现的基类，子类只需 implement `name()` / `description()` / `inputSchemaJson()` / `invoke()`。

```java
package io.kyligence.ragagent.core.tool;

/**
 * Marker base class for Spring-managed Tool beans.
 * Subclasses are auto-registered into ToolRegistry on startup.
 */
public abstract class JavaBeanTool implements Tool {
    // intentionally empty — serves as type marker for registry scanning
}
```

---

## Phase 3: DB Entity & Mapper

### File: `core/tool/ToolDef.java`

```java
package io.kyligence.ragagent.core.tool;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tool_def")
public class ToolDef {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private Long workspaceId;
    private String name;
    private String description;
    private ToolImplType implType;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String configJson;
    private Boolean enabled;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

### File: `core/tool/ToolDefMapper.java`

```java
package io.kyligence.ragagent.core.tool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.kyligence.ragagent.core.tenant.WorkspaceAware;
import org.apache.ibatis.annotations.Mapper;

@Mapper
@WorkspaceAware
public interface ToolDefMapper extends BaseMapper<ToolDef> {
}
```

---

## Phase 4: HttpTool

### File: `core/tool/HttpTool.java`

从 `ToolDef` 的 `configJson` 动态构建；使用 Spring `RestTemplate`（同步，适合工具调用）。

```java
package io.kyligence.ragagent.core.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

public class HttpTool implements Tool {

    private final ToolDef def;
    private final RestTemplate rest;
    private final ObjectMapper objectMapper;

    public HttpTool(ToolDef def, RestTemplate rest, ObjectMapper objectMapper) {
        this.def = def;
        this.rest = rest;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() { return def.getName(); }

    @Override
    public String description() { return def.getDescription(); }

    @Override
    public String inputSchemaJson() { return def.getInputSchemaJson(); }

    @Override
    public ToolResult invoke(ToolInput input, ToolContext ctx) {
        try {
            Map<String, Object> config = objectMapper.readValue(
                def.getConfigJson(), new TypeReference<>() {});
            String url = (String) config.get("url");
            String method = ((String) config.getOrDefault("method", "POST")).toUpperCase();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            @SuppressWarnings("unchecked")
            Map<String, String> extraHeaders = (Map<String, String>)
                config.getOrDefault("headers", Map.of());
            extraHeaders.forEach(headers::set);

            String body = objectMapper.writeValueAsString(input.params());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> resp = rest.exchange(url,
                HttpMethod.valueOf(method), entity, String.class);

            return ToolResult.ok(resp.getBody());
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.TOOL_INVOKE_FAILED,
                "HTTP tool invocation failed: " + e.getMessage(), e);
        }
    }
}
```

---

## Phase 5: ToolRegistry

### File: `core/tool/ToolRegistry.java`

```java
package io.kyligence.ragagent.core.tool;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {

    /** Register a tool (idempotent by name). */
    void register(Tool tool);

    /** Find tool by name. */
    Optional<Tool> find(String name);

    /** Invoke tool by name, throwing if not found. */
    ToolResult invoke(String name, ToolInput input, ToolContext ctx);

    /** List all registered tool names and descriptions. */
    List<ToolSummary> list();

    record ToolSummary(String name, String description, String inputSchemaJson) {}
}
```

### File: `core/tool/ToolRegistryImpl.java`

```java
package io.kyligence.ragagent.core.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ApplicationContext ctx;
    private final ToolDefMapper toolDefMapper;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public ToolRegistryImpl(ApplicationContext ctx, ToolDefMapper toolDefMapper,
                            RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.ctx = ctx;
        this.toolDefMapper = toolDefMapper;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** Auto-register all JavaBeanTool Spring beans on startup. */
    @PostConstruct
    void autoRegister() {
        ctx.getBeansOfType(JavaBeanTool.class).values().forEach(t -> {
            tools.put(t.name(), t);
            log.info("Registered JavaBeanTool: {}", t.name());
        });
    }

    /** Load HTTP tools from DB for a given workspace (called lazily by Agent/Workflow). */
    public void loadWorkspaceTools(Long workspaceId) {
        List<ToolDef> defs = toolDefMapper.selectList(
            Wrappers.<ToolDef>lambdaQuery()
                .eq(ToolDef::getWorkspaceId, workspaceId)
                .eq(ToolDef::getEnabled, true)
                .eq(ToolDef::getImplType, ToolImplType.HTTP));
        defs.forEach(def -> {
            tools.put(def.getName(), new HttpTool(def, restTemplate, objectMapper));
            log.info("Loaded HTTP tool: ", def.getName());
        });
    }

    @Override
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    @Override
    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public ToolResult invoke(String name, ToolInput input, ToolContext ctx) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new PlatformException(ErrorCode.TOOL_NOT_FOUND, "Tool not found: " + name);
        }
        return tool.invoke(input, ctx);
    }

    @Override
    public List<ToolSummary> list() {
        return tools.values().stream()
            .map(t -> new ToolSummary(t.name(), t.description(), t.inputSchemaJson()))
            .toList();
    }
}
```

---

## Phase 6: REST Controller

### File: `server/.../controller/ToolController.java`

```java
package io.kyligence.ragagent.server.controller;

import io.kyligence.ragagent.core.tool.ToolDef;
import io.kyligence.ragagent.core.tool.ToolDefMapper;
import io.kyligence.ragagent.core.tool.ToolImplType;
import io.kyligence.ragagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/tools")
@RequiredArgsConstructor
public class ToolController {

    public record CreateToolRequest(
            @NotBlank String name,
            String description,
            @NotBlank String implType,   // must be HTTP for API-managed tools
            String inputSchemaJson,
            String configJson) {}

    private final ToolDefMapper toolDefMapper;

    @GetMapping
    public ApiResponse<List<ToolDef>> list(@PathVariable Long workspaceId) {
        return ApiResponse.ok(toolDefMapper.selectList(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.<ToolDef>lambdaQuery()
                .eq(ToolDef::getWorkspaceId, workspaceId)));
    }

    @PostMapping
    public ApiResponse<ToolDef> create(@PathVariable Long workspaceId,
                                       @Valid @RequestBody CreateToolRequest req) {
        ToolDef def = new ToolDef();
        def.setWorkspaceId(workspaceId);
        def.setName(req.name());
        def.setDescription(req.description());
        def.setImplType(ToolImplType.valueOf(req.implType()));
        def.setInputSchemaJson(req.inputSchemaJson());
        def.setConfigJson(req.configJson());
        def.setEnabled(true);
        def.setCreatedAt(LocalDateTime.now());
        def.setUpdatedAt(LocalDateTime.now());
        toolDefMapper.insert(def);
        return ApiResponse.ok(def);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        toolDefMapper.deleteById(id);
        return ApiResponse.ok(null);
    }
}
```

---

## Phase 7: Tests

### File: `core/tool/ToolRegistryImplTest.java`

```java
package io.kyligence.ragagent.core.tool;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolRegistryImplTest {

    @Mock private ApplicationContext applicationContext;
    @Mock private ToolDefMapper toolDefMapper;
    @Mock private RestTemplate restTemplate;

    private ToolRegistryImpl registry;

    @BeforeEach
    void setUp() {
        when(applicationContext.getBeansOfType(JavaBeanTool.class)).thenReturn(Map.of());
        registry = new ToolRegistryImpl(applicationContext, toolDefMapper,
                restTemplate, new ObjectMapper());
        registry.autoRegister();
    }

    @Test
    void registerAndFind() {
        Tool tool = stubTool("echo");
        registry.register(tool);
        assertThat(registry.find("echo")).contains(tool);
    }

    @Test
    void findReturnsEmptyForUnknown() {
        assertThat(registry.find("unknown")).isEmpty();
    }

    @Test
    void invokeThrowsWhenNotFound() {
        assertThatThrownBy(() ->
            registry.invoke("missing", new ToolInput(Map.of()), new ToolContext("r", "w", 1L)))
            .isInstanceOf(PlatformException.class);
    }

    @Test
    void invokeCallsTool() {
        Tool tool = stubTool("greet");
        when(tool.invoke(any(), any())).thenReturn(ToolResult.ok("hi"));
        registry.register(tool);

        ToolResult result = registry.invoke("greet",
            new ToolInput(Map.of()), new ToolContext("r", "w", 1L));
        assertThat(result.success()).isTrue();
        assertThat(result.output().value()).isEqualTo("hi");
    }

    @Test
    void autoRegisterPicksUpJavaBeanTools() {
        JavaBeanTool bean = mock(JavaBeanTool.class);
        when(bean.name()).thenReturn("calc");
        when(applicationContext.getBeansOfType(JavaBeanTool.class))
            .thenReturn(Map.of("calc", bean));

        registry.autoRegister();
        assertThat(registry.find("calc")).contains(bean);
    }

    @Test
    void listReturnsAllTools() {
        registry.register(stubTool("a"));
        registry.register(stubTool("b"));
        assertThat(registry.list()).extracting(ToolRegistry.ToolSummary::name)
            .contains("a", "b");
    }

    private Tool stubTool(String name) {
        Tool t = mock(Tool.class);
        when(t.name()).thenReturn(name);
        return t;
    }
}
```

### File: `core/tool/HttpToolTest.java`

```java
package io.kyligence.ragagent.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpToolTest {

    @Test
    void invokesHttpEndpointAndReturnsBody() throws Exception {
        ToolDef def = new ToolDef();
        def.setName("search");
        def.setDescription("search tool");
        def.setConfigJson("{\"url\":\"http://test/search\",\"method\":\"POST\"}");

        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"result\":\"found\"}"));

        HttpTool tool = new HttpTool(def, rest, new ObjectMapper());
        ToolResult result = tool.invoke(new ToolInput(Map.of("q", "hello")),
            new ToolContext("run1", "ws1", 1L));

        assertThat(result.success()).isTrue();
        assertThat(result.output().value()).isEqualTo("{\"result\":\"found\"}");
    }
}
```

---

## Acceptance Criteria

1. `mvn -pl platform-core -am compile` 通过
2. `mvn -pl platform-core -Dtest=ToolRegistryImplTest,HttpToolTest test` 全绿
3. `mvn -pl server -am compile` 通过（ToolController）
4. 已有测试不被破坏
5. Liquibase `011-tool-def.yaml` 被 master changelog 自动加载
6. `JavaBeanTool` 子类只需实现接口，无需手动注册

## Dependencies

- `RestTemplate` Bean 需在 `server` 的配置类里声明（或通过 `RestTemplateBuilder` 自动创建）
- Jackson `ObjectMapper` 由 Spring Boot 自动配置
- `ErrorCode.TOOL_NOT_FOUND` / `TOOL_INVOKE_FAILED` 已存在 ✓（B1 Phase 0 引入）
- `WorkspaceAware` 注解已在 Plan A 创建 ✓

## 设计决策

| 决策 | 理由 |
|------|------|
| JAVA_BEAN 不存 DB | Spring Bean 生命周期即注册，DB 存储 HTTP/外部工具 |
| 注册表用 ConcurrentHashMap | 单进程内线程安全；无分布式需求 |
| HTTP 工具用 RestTemplate | 同步调用符合工具语义；WebClient 异步在此场景多余 |
| WORKFLOW_REF / RETRIEVER_REF 暂不实现 | 依赖 Plan C/D；占位接口保留扩展点 |
| loadWorkspaceTools 懒加载 | 避免启动时全量加载所有 workspace 的工具 |
